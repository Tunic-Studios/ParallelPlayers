package com.tunic.parallel.client.config;

import com.tunic.parallel.ParallelPlayers;
import com.tunic.parallel.character.BackupTiming;
import com.tunic.parallel.character.CharacterStore;
import com.tunic.parallel.config.ConfigFile;

/** Settings for this player's game only, in {@code config/parallel/client.json}. */
public final class ClientConfig {

    private static final String FILE = "client";

    private static volatile ClientConfig current = new ClientConfig();

    public static final int MAX_BACKUP_LIMIT = 20;

    /** Whether the paper doll on the character screens turns to look at the cursor. */
    public boolean dollFollowsCursor = true;

    /** Whether the paper doll wears the character's armor, and anything else worn on the head. */
    public boolean dollShowsArmor = true;

    /** Whether the paper doll holds the character's items. */
    public boolean dollShowsItems = true;

    /** How many backups of its player data each character keeps, 0 to {@value #MAX_BACKUP_LIMIT}; 0 is none. */
    public int backupLimit = CharacterStore.DEFAULT_BACKUP_LIMIT;

    /** When characters are backed up: {@code ENTER_WORLD}, {@code WORLD_SAVE} or {@code GAME_LAUNCH}. */
    public BackupTiming backupTiming = BackupTiming.ENTER_WORLD;

    public static ClientConfig get() {
        return current;
    }

    public static void reload() {
        ConfigFile.load(FILE, ClientConfig.class, ClientConfig::new).apply();
    }

    /** Writes these settings to the file and makes them the current ones. */
    public void save() {
        ConfigFile.save(FILE, this);
        this.apply();
    }

    private void apply() {
        this.backupLimit = Math.clamp(this.backupLimit, 0, MAX_BACKUP_LIMIT);
        if (this.backupTiming == null) {
            // Missing, or a value the file should not have.
            this.backupTiming = BackupTiming.ENTER_WORLD;
        }
        current = this;
        // Characters are saved by the server built into the client, which reads these from the store.
        ParallelPlayers.characters().setBackupLimit(this.backupLimit);
        ParallelPlayers.characters().setBackupTiming(this.backupTiming);
    }
}
