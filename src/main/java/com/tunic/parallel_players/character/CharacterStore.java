package com.tunic.parallel_players.character;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
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
import java.util.stream.Stream;

/**
 * On-disk store of characters, independent of any world.
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
 *     backup/          previous playerdata.dat copies, newest first
 * </pre>
 */
public final class CharacterStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("parallelplayers");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    static final String META_FILE = "meta.json";
    static final String DATA_FILE = "playerdata.dat";
    static final String APPEARANCE_FILE = "appearance.dat";
    static final String SKIN_FILE = "skin.png";
    static final String STATS_FILE = "stats.json";
    static final String ADVANCEMENTS_FILE = "advancements.json";
    static final String BACKUP_DIR = "backup";
    static final int MAX_BACKUPS = 5;

    private final Path root;
    /** Parsed metadata by id; every change goes through this class, so it is kept in step. */
    private final Map<UUID, CharacterMeta> cache = new ConcurrentHashMap<>();

    public CharacterStore(Path root) {
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

    public CharacterMeta create(String name) {
        long now = System.currentTimeMillis();
        CharacterMeta meta = new CharacterMeta(UUID.randomUUID(), sanitizeName(name), now, now, null, false, null);
        writeMeta(meta);
        return meta;
    }

    /**
     * Creates a character that already has its mode and skin chosen.
     *
     * @param mode  the character's mode, or {@code null} to let it adopt the first world it joins
     * @param image the PNG for {@link CharacterSkin#CUSTOM}, otherwise ignored
     */
    public CharacterMeta create(String name, @Nullable CharacterMode mode, CharacterSkin skin, byte @Nullable [] image) {
        CharacterMeta meta = create(name);
        if (mode != null) {
            setMode(meta.id(), mode);
        }
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

    /**
     * Gives a character its mode. A mode can only be set once; it stays fixed so that, for example, a
     * hardcore character cannot be turned into a survival one afterwards.
     *
     * @throws IllegalStateException if the character already has a different mode
     */
    public void setMode(UUID id, CharacterMode mode) {
        CharacterMeta meta = require(id);
        CharacterMode current = meta.modeChoice();
        if (current == mode) {
            return;
        }
        if (current != null) {
            throw new IllegalStateException("Character " + id + " is already " + current);
        }
        writeMeta(meta.withMode(mode));
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

    /**
     * Keeps a copy of the character's current player data in {@code backup/}, newest {@value #MAX_BACKUPS}
     * kept. Meant to be called once per play session, before the session starts changing the data.
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

    private void backup(Path dir, Path file) throws IOException {
        Path backups = dir.resolve(BACKUP_DIR);
        Files.createDirectories(backups);
        Files.copy(file, backups.resolve(System.currentTimeMillis() + ".dat"), StandardCopyOption.REPLACE_EXISTING);
        try (Stream<Path> old = Files.list(backups)) {
            List<Path> sorted = old.sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed()).toList();
            for (Path p : sorted.stream().skip(MAX_BACKUPS).toList()) {
                Files.deleteIfExists(p);
            }
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
            if (meta == null || meta.id() == null || meta.name() == null
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
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sanitizeName(String name) {
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Character name must not be empty");
        }
        return trimmed.length() > 32 ? trimmed.substring(0, 32) : trimmed;
    }
}
