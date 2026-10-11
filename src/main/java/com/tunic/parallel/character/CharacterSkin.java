package com.tunic.parallel.character;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Which skin a character uses.
 *
 * @param id   {@code null} to use the account's own skin, {@link #CUSTOM} for an imported image kept in
 *             the character's folder, otherwise the name of one of vanilla's default skins ({@link #PRESETS})
 * @param slim whether the model has slim (3 px) arms instead of wide ones
 */
public record CharacterSkin(@Nullable String id, boolean slim) {

    public static final String CUSTOM = "custom";

    public static final CharacterSkin ACCOUNT = new CharacterSkin(null, false);

    /** Vanilla's default skins; each exists in a wide and a slim variant. */
    public static final List<String> PRESETS = List.of("steve", "alex", "ari", "efe", "kai", "makena", "noor", "sunny", "zuri");

    public boolean isAccount() {
        return id == null;
    }

    public boolean isCustom() {
        return CUSTOM.equals(id);
    }

    public boolean isPreset() {
        return id != null && PRESETS.contains(id);
    }

    public CharacterSkin withSlim(boolean slim) {
        return new CharacterSkin(id, slim);
    }
}
