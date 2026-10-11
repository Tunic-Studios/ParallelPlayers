package com.tunic.parallel.character;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The rules for character names: those of Minecraft usernames (3 to 16 letters, digits and underscores),
 * except that single spaces between words are allowed too. Mojang's own check for inappropriate names runs
 * on the client when a name is chosen; these rules are the part every side can check.
 */
public final class CharacterNames {

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 16;

    private static final Pattern ALLOWED_CHARACTERS = Pattern.compile("[A-Za-z0-9_ ]*");
    private static final Pattern DISALLOWED_CHARACTER = Pattern.compile("[^A-Za-z0-9_ ]");

    /** What is wrong with a name, worst first. */
    public enum Problem {
        INVALID_CHARACTERS,
        SPACING,
        TOO_SHORT,
        TOO_LONG;

        public String translationKey() {
            return "parallel.characters.name.problem." + name().toLowerCase(Locale.ROOT);
        }
    }

    private CharacterNames() {
    }

    /** What is wrong with the name, or empty if it is a valid character name. */
    public static Optional<Problem> problem(String name) {
        if (!ALLOWED_CHARACTERS.matcher(name).matches()) {
            return Optional.of(Problem.INVALID_CHARACTERS);
        }
        if (name.startsWith(" ") || name.endsWith(" ") || name.contains("  ")) {
            return Optional.of(Problem.SPACING);
        }
        if (name.length() < MIN_LENGTH) {
            return Optional.of(Problem.TOO_SHORT);
        }
        if (name.length() > MAX_LENGTH) {
            return Optional.of(Problem.TOO_LONG);
        }
        return Optional.empty();
    }

    /** The text with every character a name may not have taken out, for cleaning up what is typed. */
    public static String withoutDisallowedCharacters(String text) {
        return DISALLOWED_CHARACTER.matcher(text).replaceAll("");
    }

    /**
     * The usernames to ask Mojang about for a valid name: Mojang's check does not take spaces, so the name
     * with them taken out, and each word long enough to be a username on its own.
     */
    public static List<String> usernamesToCheck(String name) {
        List<String> usernames = new ArrayList<>();
        String joined = name.replace(" ", "");
        if (joined.length() >= MIN_LENGTH) {
            usernames.add(joined);
        }
        for (String word : name.split(" ")) {
            if (word.length() >= MIN_LENGTH && !usernames.contains(word)) {
                usernames.add(word);
            }
        }
        return usernames;
    }
}
