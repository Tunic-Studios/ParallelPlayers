package com.tunic.parallel.character;

import com.google.common.hash.Hashing;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Which skin a character uses.
 *
 * @param id   {@code null} to use the account's own skin, {@link #CUSTOM} for an imported image kept in
 *             the character's folder, otherwise the name of one of vanilla's default skins ({@link #PRESETS})
 * @param slim whether the model has slim (3 px) arms instead of wide ones
 */
public record CharacterSkin(@Nullable String id, boolean slim) {

    public static final String CUSTOM = "custom";

    /*
     * Skin images follow the rules of Mojang's own skin upload, so any skin that works on an account works for
     * a character and the other way round: a PNG of at most 24 KB, 64x64 or the legacy 64x32 layout.
     */
    /** Largest skin image file, in bytes. */
    public static final int MAX_IMAGE_BYTES = 24 * 1024;
    public static final int IMAGE_WIDTH = 64;
    public static final int IMAGE_HEIGHT = 64;
    /** Height of a skin in the layout from before 1.8, without separate left limbs or outer layers. */
    public static final int LEGACY_IMAGE_HEIGHT = 32;

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    /** "IHDR", the chunk every PNG starts with, which holds its size. */
    private static final int PNG_HEADER_CHUNK = 0x49484452;

    /** What {@link #hash} returns: SHA-256 in lowercase hex. */
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

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

    /** Why an image cannot be a skin. */
    public enum ImageProblem {
        TOO_LARGE,
        NOT_PNG,
        WRONG_SIZE;

        public String translationKey() {
            return "parallel.skin.error." + name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * What keeps the bytes from being a skin image, if anything; see {@link #MAX_IMAGE_BYTES}. The size is read
     * from the PNG's header without decoding it, so anything that decodes an image checks it with this first: a
     * small file can claim to be huge, and decoding it would take enormous amounts of memory.
     */
    public static Optional<ImageProblem> imageProblem(byte[] png) {
        if (png.length > MAX_IMAGE_BYTES) {
            return Optional.of(ImageProblem.TOO_LARGE);
        }
        if (png.length < 24) {
            return Optional.of(ImageProblem.NOT_PNG);
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (png[i] != PNG_SIGNATURE[i]) {
                return Optional.of(ImageProblem.NOT_PNG);
            }
        }
        ByteBuffer header = ByteBuffer.wrap(png);
        if (header.getInt(12) != PNG_HEADER_CHUNK) {
            return Optional.of(ImageProblem.NOT_PNG);
        }
        int width = header.getInt(16);
        int height = header.getInt(20);
        return width == IMAGE_WIDTH && (height == IMAGE_HEIGHT || height == LEGACY_IMAGE_HEIGHT)
                ? Optional.empty() : Optional.of(ImageProblem.WRONG_SIZE);
    }

    /** Whether the bytes are a valid skin image; see {@link #imageProblem}. */
    public static boolean isSkinImage(byte[] png) {
        return imageProblem(png).isEmpty();
    }

    /**
     * Names a skin image by its content, so clients can keep the images they have seen and only fetch the
     * ones they have not; see {@link #isHash}.
     */
    public static String hash(byte[] png) {
        return Hashing.sha256().hashBytes(png).toString();
    }

    /**
     * Whether the text has the form of a {@link #hash}. Hashes from the network are checked with this before
     * use, as they also name files.
     */
    public static boolean isHash(String text) {
        return HASH.matcher(text).matches();
    }

    public CharacterSkin withSlim(boolean slim) {
        return new CharacterSkin(id, slim);
    }
}
