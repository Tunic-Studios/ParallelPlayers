package com.tunic.parallel.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.tunic.parallel.ParallelPlayers;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Reads and writes the mod's JSON config files in {@code config/parallel/}. A config is a plain class whose
 * public fields are the options, each initialized to its default; options missing from the file keep that
 * default, and the file is written back so that new options show up in it.
 */
public final class ConfigFile {

    private static final Logger LOGGER = LoggerFactory.getLogger(ParallelPlayers.MOD_ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ConfigFile() {
    }

    /**
     * Loads {@code config/parallel/<name>.json}, or the defaults if it does not exist. A file that cannot be
     * read is left untouched, so a typo does not wipe the player's settings, and the defaults are used.
     */
    public static <T> T load(String name, Class<T> type, Supplier<T> defaults) {
        Path file = path(name);
        if (!Files.isRegularFile(file)) {
            T config = defaults.get();
            save(name, config);
            return config;
        }
        try {
            T config = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), type);
            if (config == null) {
                config = defaults.get();
            }
            save(name, config);
            return config;
        } catch (IOException | JsonParseException e) {
            LOGGER.error("Could not read {}, using the default settings until it is fixed", file, e);
            return defaults.get();
        }
    }

    public static void save(String name, Object config) {
        Path file = path(name);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(config) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("Could not save {}", file, e);
        }
    }

    private static Path path(String name) {
        return FabricLoader.getInstance().getConfigDir().resolve(ParallelPlayers.MOD_ID).resolve(name + ".json");
    }
}
