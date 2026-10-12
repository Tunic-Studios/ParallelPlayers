package com.tunic.parallel.character;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * On-disk storage of characters, independent of any world.
 *
 * <pre>
 * root/
 *   &lt;uuid&gt;/
 *     meta.json        {@link CharacterMeta}
 *     playerdata.dat   opaque player data blob (written by the world-swap code)
 *     appearance.dat   what the character wears and holds, for the menu
 *     skin.png         imported skin image, when the character uses a custom skin
 *     stats.json       statistics, read and written by vanilla's stats counter
 *     advancements.json  advancement progress, read and written by vanilla
 *     backup/          previous playerdata.dat copies, the newest {@link #setBackupLimit limit} of them, plus
 *                      every copy that lost a sync conflict ({@link #keepConflictCopy}), which are never pruned
 *     synced.json      client only: the save last synced with each server ({@link #syncedSaveId})
 * </pre>
 */
public final class CharacterStorage {

    private static final Logger LOGGER = LoggerFactory.getLogger("parallel");
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    static final String META_FILE = "meta.json";
    static final String DATA_FILE = "playerdata.dat";
    static final String APPEARANCE_FILE = "appearance.dat";
    static final String SKIN_FILE = "skin.png";
    static final String STATS_FILE = "stats.json";
    static final String ADVANCEMENTS_FILE = "advancements.json";
    static final String BACKUP_DIR = "backup";
    static final String SYNCED_FILE = "synced.json";
    /** Name prefix of backups kept after losing a sync conflict; the newest-n pruning leaves them alone. */
    static final String CONFLICT_PREFIX = "conflict-";
    public static final int DEFAULT_BACKUP_LIMIT = 5;

    private final Path root;
    /** Parsed metadata by id; every change goes through this class, so it is kept in step. */
    private final Map<UUID, CharacterMeta> cache = new ConcurrentHashMap<>();
    private volatile int backupLimit = DEFAULT_BACKUP_LIMIT;
    private volatile BackupTiming backupTiming = BackupTiming.ENTER_WORLD;
    /** Characters backed up since the game started, for {@link BackupTiming#GAME_LAUNCH}. */
    private final Set<UUID> backedUpThisLaunch = ConcurrentHashMap.newKeySet();

    public CharacterStorage(Path root) {
        this.root = root;
    }

    /** All readable characters, most recently played first. Broken folders are skipped. */
    public List<CharacterMeta> list() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(root)) {
            return dirs.filter(Files::isDirectory)
                    .map(this::readMeta)
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparingLong(CharacterMeta::lastPlayedAt).reversed())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list characters in " + root, e);
        }
    }

    public Optional<CharacterMeta> get(UUID id) {
        CharacterMeta cached = cache.get(id);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<CharacterMeta> read = readMeta(dir(id));
        read.ifPresent(meta -> cache.put(id, meta));
        return read;
    }

    /**
     * Creates a character.
     *
     * @param gamemode the character's gamemode, fixed from now on
     * @param image    the PNG for {@link CharacterSkin#CUSTOM}, otherwise ignored
     */
    public CharacterMeta create(String name, CharacterGamemode gamemode, CharacterSkin skin, byte @Nullable [] image) {
        long now = System.currentTimeMillis();
        CharacterMeta meta = new CharacterMeta(UUID.randomUUID(), sanitizeName(name), now, now, null, false, gamemode, 0L);
        writeMeta(meta);
        setSkin(meta.id(), skin, image);
        return get(meta.id()).orElse(meta);
    }

    /**
     * Changes the character's skin.
     *
     * @param image the PNG to store when {@code skin} is {@link CharacterSkin#CUSTOM}; if {@code null} the
     *              image already stored is kept. A custom skin without any image falls back to the account skin.
     */
    public void setSkin(UUID id, CharacterSkin skin, byte @Nullable [] image) {
        require(id);
        Path file = skinFile(id);
        try {
            if (skin.isCustom() && image != null) {
                writeAtomically(file, image);
            } else if (!skin.isCustom()) {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write skin for " + id, e);
        }
        CharacterSkin effective = skin.isCustom() && !Files.isRegularFile(file) ? CharacterSkin.ACCOUNT : skin;
        writeMeta(require(id).withSkin(effective));
    }

    /** Where an imported skin image is kept; only present for {@link CharacterSkin#CUSTOM}. */
    public Path skinFile(UUID id) {
        return dir(id).resolve(SKIN_FILE);
    }

    public void rename(UUID id, String newName) {
        writeMeta(require(id).withName(sanitizeName(newName)));
    }

    /** Marks the character as played right now. */
    public void touch(UUID id) {
        writeMeta(require(id).withLastPlayedAt(System.currentTimeMillis()));
    }

    public void delete(UUID id) {
        cache.remove(id);
        Path dir = dir(id);
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete character " + id, e);
        }
    }

    /** The character's saved player data, or empty for a character that has never been played. */
    public Optional<byte[]> readPlayerData(UUID id) {
        Path file = dir(id).resolve(DATA_FILE);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read player data for " + id, e);
        }
    }

    /** The character's menu appearance snapshot (see {@link CharacterLoadout}), if one was saved. */
    public Optional<byte[]> readAppearance(UUID id) {
        Path file = dir(id).resolve(APPEARANCE_FILE);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read appearance for " + id, e);
        }
    }

    public void writeAppearance(UUID id, byte[] data) {
        require(id);
        try {
            writeAtomically(dir(id).resolve(APPEARANCE_FILE), data);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write appearance for " + id, e);
        }
    }

    /** Replaces the character's player data, written atomically so a crash cannot leave a half-written file. */
    public void writePlayerData(UUID id, byte[] data) {
        require(id);
        try {
            writeAtomically(dir(id).resolve(DATA_FILE), data);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write player data for " + id, e);
        }
    }

    /** Records that the character's player data was saved again; see {@link CharacterMeta#saveId()}. */
    public void markSaved(UUID id) {
        CharacterMeta meta = require(id);
        writeMeta(meta.withSaveId(ThreadLocalRandom.current().nextLong()));
    }

    /**
     * The {@linkplain CharacterMeta#saveId() save} of the character that this client and a server last agreed
     * on: the server's copy as it was last received from it, or this copy as the server last accepted it.
     *
     * @param world the server's world id
     * @return empty if the character has never been synced with that server
     */
    public OptionalLong syncedSaveId(UUID id, String world) {
        JsonObject synced = readSynced(id);
        JsonElement saveId = synced.get(world);
        return saveId != null && saveId.isJsonPrimitive() ? OptionalLong.of(saveId.getAsLong()) : OptionalLong.empty();
    }

    /** Records the save this client and a server now agree on; see {@link #syncedSaveId}. */
    public void setSyncedSaveId(UUID id, String world, long saveId) {
        JsonObject synced = readSynced(id);
        synced.addProperty(world, saveId);
        try {
            writeAtomically(dir(id).resolve(SYNCED_FILE), GSON.toJson(synced).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to record the sync state of character " + id, e);
        }
    }

    private JsonObject readSynced(UUID id) {
        Path file = dir(id).resolve(SYNCED_FILE);
        if (!Files.isRegularFile(file)) {
            return new JsonObject();
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            return root.isJsonObject() ? root.getAsJsonObject() : new JsonObject();
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Ignoring unreadable sync state in {}", file, e);
            return new JsonObject();
        }
    }

    /**
     * Keeps the character's current player data as a backup that is never pruned, before a copy from
     * elsewhere replaces it after both had changed.
     */
    public void keepConflictCopy(UUID id) {
        Path file = dir(id).resolve(DATA_FILE);
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            Path backups = dir(id).resolve(BACKUP_DIR);
            Files.createDirectories(backups);
            Files.copy(file, unusedBackupName(backups, CONFLICT_PREFIX));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to keep the conflicting copy of character " + id, e);
        }
    }

    /**
     * Everything about the character that travels to and from a server, or empty if there is no such character.
     *
     * @param withSkin include the imported skin image: for the client sending its character to a server
     */
    public Optional<CharacterBundle> export(UUID id, boolean withSkin) {
        return get(id).map(meta -> new CharacterBundle(meta,
                readOptional(dir(id).resolve(DATA_FILE)),
                readOptional(dir(id).resolve(APPEARANCE_FILE)),
                readOptionalText(statsFile(id)),
                readOptionalText(advancementsFile(id)),
                withSkin && meta.skinChoice().isCustom() ? skinImage(id) : null));
    }

    /** The character's imported skin image, if it has a valid one; see {@link CharacterSkin#imageProblem}. */
    public byte @Nullable [] skinImage(UUID id) {
        byte[] image = readOptional(skinFile(id));
        return image != null && CharacterSkin.isSkinImage(image) ? image : null;
    }

    /** Takes the name and skin of a newer copy kept elsewhere. */
    public void importIdentity(CharacterBundle incoming) {
        CharacterMeta from = incoming.meta();
        CharacterMeta local = require(from.id());
        writeMeta(new CharacterMeta(local.id(), sanitizeName(from.name()), local.createdAt(), local.lastPlayedAt(),
                from.skin(), from.slim(), local.gamemode(), local.saveId()));
        importSkinImage(incoming);
    }

    /** Keeps the bundle's skin image if its character uses an imported skin, and removes a stale one otherwise. */
    private void importSkinImage(CharacterBundle bundle) {
        Path file = skinFile(bundle.meta().id());
        try {
            Files.createDirectories(file.getParent());
            if (!bundle.meta().skinChoice().isCustom()) {
                Files.deleteIfExists(file);
            } else if (bundle.skinImage() != null) {
                writeAtomically(file, bundle.skinImage());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store the skin of character " + bundle.meta().id(), e);
        }
    }

    /**
     * Stores a character received from the other side, replacing this copy. Parts the bundle does not have
     * are left as they are.
     *
     * @param keepIdentity keep this copy's name, skin and creation time: the client passes {@code true}, since
     *                     those are only ever changed in its own menus while the server's copy may be older
     * @param backupFirst  back up the current player data first
     */
    public void importBundle(CharacterBundle bundle, boolean keepIdentity, boolean backupFirst) {
        CharacterMeta incoming = bundle.meta();
        UUID id = incoming.id();
        Optional<CharacterMeta> existing = get(id);
        CharacterMeta meta = incoming.withName(sanitizeName(incoming.name()));
        if (keepIdentity && existing.isPresent()) {
            CharacterMeta local = existing.get();
            meta = new CharacterMeta(id, local.name(), local.createdAt(), incoming.lastPlayedAt(),
                    local.skin(), local.slim(), local.gamemode(), incoming.saveId());
        }
        if (backupFirst) {
            backupPlayerData(id);
        }
        try {
            Files.createDirectories(dir(id));
            if (bundle.playerData() != null) {
                writeAtomically(dir(id).resolve(DATA_FILE), bundle.playerData());
            }
            if (bundle.appearance() != null) {
                writeAtomically(dir(id).resolve(APPEARANCE_FILE), bundle.appearance());
            }
            if (bundle.stats() != null) {
                writeAtomically(statsFile(id), bundle.stats().getBytes(StandardCharsets.UTF_8));
            }
            if (bundle.advancements() != null) {
                writeAtomically(advancementsFile(id), bundle.advancements().getBytes(StandardCharsets.UTF_8));
            }
            if (!keepIdentity) {
                importSkinImage(bundle);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store character " + id, e);
        }

        writeMeta(meta);
    }

    private static byte @Nullable [] readOptional(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
    }

    private static @Nullable String readOptionalText(Path file) {
        byte[] bytes = readOptional(file);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Keeps a copy of the character's current player data in {@code backup/}, newest {@link #setBackupLimit limit}
     * kept. Meant to be called before a play session starts changing the data. Nothing new is written when
     * the newest backup already holds exactly this data, so calling it more than once per session is harmless.
     */
    public void backupPlayerData(UUID id) {
        Path dir = dir(id);
        Path file = dir.resolve(DATA_FILE);
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            backup(dir, file);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to back up player data for " + id, e);
        }
    }

    /**
     * How many backups each character keeps; older ones are deleted at the next backup. 0 turns backups off,
     * and the existing ones are then removed the next time the character is played.
     */
    public void setBackupLimit(int limit) {
        this.backupLimit = Math.max(0, limit);
    }

    public void setBackupTiming(BackupTiming timing) {
        this.backupTiming = timing;
    }

    /** Backs the character up if the backup timing calls for it when it enters a world. */
    public void onEnterWorld(UUID id) {
        BackupTiming timing = this.backupTiming;
        if (timing == BackupTiming.ENTER_WORLD
                || timing == BackupTiming.GAME_LAUNCH && this.backedUpThisLaunch.add(id)) {
            backupPlayerData(id);
        }
    }

    /** Backs the character up if the backup timing calls for it before its data is saved over. */
    public void beforeSave(UUID id) {
        if (this.backupTiming == BackupTiming.WORLD_SAVE) {
            backupPlayerData(id);
        }
    }

    private void backup(Path dir, Path file) throws IOException {
        int limit = this.backupLimit;
        Path backups = dir.resolve(BACKUP_DIR);
        Files.createDirectories(backups);
        List<Path> existing;
        try (Stream<Path> old = Files.list(backups)) {
            // Newest first: the names are the times the backups were made.
            existing = old.filter(p -> !p.getFileName().toString().startsWith(CONFLICT_PREFIX)).sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed()).toList();
        }
        List<Path> kept = new ArrayList<>(existing);
        if (limit > 0 && (existing.isEmpty() || Files.mismatch(existing.getFirst(), file) != -1L)) {
            Path copy = unusedBackupName(backups, "");
            Files.copy(file, copy);
            kept.addFirst(copy);
        }
        for (Path p : kept.stream().skip(limit).toList()) {
            Files.deleteIfExists(p);
        }
    }

    /**
     * A backup file named after the current time, or a moment later if two backups are made in the same
     * millisecond, so neither replaces the other. The names still sort by age.
     */
    private static Path unusedBackupName(Path backups, String prefix) {
        long time = System.currentTimeMillis();
        Path file = backups.resolve(prefix + time + ".dat");
        while (Files.exists(file)) {
            file = backups.resolve(prefix + ++time + ".dat");
        }
        return file;
    }

    /**
     * How long the character has been played, in ticks, from vanilla's own play-time statistic in its
     * stats file. That file is written when the game saves, so this lags by up to an autosave interval.
     *
     * @return 0 if the character has no statistics yet or they cannot be read
     */
    public long playTimeTicks(UUID id) {
        return playTimeTicks(statsFile(id));
    }

    /**
     * The play-time statistic in a vanilla stats file, in ticks.
     *
     * @return 0 if the file does not exist or cannot be read
     */
    public static long playTimeTicks(Path file) {
        if (!Files.isRegularFile(file)) {
            return 0L;
        }
        try {
            return playTimeTicks(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException _) {
            return 0L;
        }
    }

    /**
     * The play-time statistic in the contents of a vanilla stats file, in ticks.
     *
     * @return 0 if there is none or it cannot be read
     */
    public static long playTimeTicks(@Nullable String json) {
        if (json == null) {
            return 0L;
        }
        try {
            JsonElement root = JsonParser.parseString(json);
            JsonElement time = root.getAsJsonObject()
                    .getAsJsonObject("stats")
                    .getAsJsonObject("minecraft:custom")
                    .get("minecraft:play_time");
            return time == null ? 0L : time.getAsLong();
        } catch (RuntimeException _) {
            // A missing section (NullPointerException from the chain), wrong types or bad JSON all mean "unknown".
            return 0L;
        }
    }

    /** Where the character's statistics live, in vanilla's own stats JSON format. */
    public Path statsFile(UUID id) {
        return dir(id).resolve(STATS_FILE);
    }

    /** Where the character's advancement progress lives, in vanilla's own advancements JSON format. */
    public Path advancementsFile(UUID id) {
        return dir(id).resolve(ADVANCEMENTS_FILE);
    }

    private CharacterMeta require(UUID id) {
        return get(id).orElseThrow(() -> new IllegalArgumentException("Unknown character " + id));
    }

    private Path dir(UUID id) {
        return root.resolve(id.toString());
    }

    private Optional<CharacterMeta> readMeta(Path dir) {
        Path file = dir.resolve(META_FILE);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            CharacterMeta meta = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), CharacterMeta.class);
            if (meta == null || meta.id() == null || meta.name() == null || meta.gamemode() == null
                    || !meta.id().toString().equals(dir.getFileName().toString())) {
                LOGGER.warn("Ignoring invalid character metadata in {}", dir);
                return Optional.empty();
            }
            return Optional.of(meta);
        } catch (IOException | JsonParseException e) {
            LOGGER.warn("Ignoring unreadable character metadata in {}", dir, e);
            return Optional.empty();
        }
    }

    private void writeMeta(CharacterMeta meta) {
        Path dir = dir(meta.id());
        try {
            Files.createDirectories(dir);
            writeAtomically(dir.resolve(META_FILE), GSON.toJson(meta).getBytes(StandardCharsets.UTF_8));
            cache.put(meta.id(), meta);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write character " + meta.id(), e);
        }
    }

    private static void writeAtomically(Path target, byte[] data) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, data);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException _) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * The name without surrounding spaces, if it is a valid character name.
     *
     * @throws IllegalArgumentException if it is not; see {@link CharacterNames}
     */
    private static String sanitizeName(@Nullable String name) {
        String trimmed = name == null ? "" : name.strip();
        CharacterNames.problem(trimmed).ifPresent(problem -> {
            throw new IllegalArgumentException("Invalid character name \"" + trimmed + "\": " + problem);
        });
        return trimmed;
    }
}
