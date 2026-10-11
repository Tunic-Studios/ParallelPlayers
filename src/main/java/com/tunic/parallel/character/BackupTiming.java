package com.tunic.parallel.character;

import java.util.Locale;

/** When a character's player data is backed up. A backup identical to the newest one is never written. */
public enum BackupTiming {
    /** Each time the character enters a world, before anything in it changes the character. */
    ENTER_WORLD,
    /** Before every save overwrites the data, autosaves included. */
    WORLD_SAVE,
    /** The first time the character enters a world after the game was started. */
    GAME_LAUNCH;

    public String translationKey() {
        return "parallel.settings.backup_timing." + name().toLowerCase(Locale.ROOT);
    }
}
