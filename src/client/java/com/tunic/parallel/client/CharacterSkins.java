package com.tunic.parallel.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.tunic.parallel.ParallelPlayers;
import com.tunic.parallel.character.CharacterMeta;
import com.tunic.parallel.character.CharacterSkin;
import com.tunic.parallel.config.ServerConfig;
import com.tunic.parallel.mixin.client.SkinTextureDownloaderAccessor;
import com.tunic.parallel.network.CharacterPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Turns a character's {@link CharacterSkin} choice into the {@link PlayerSkin} the game renders with.
 * Everything except the body texture and arm model (cape, elytra) is taken from the account's skin.
 */
public final class CharacterSkins {

    private static final Logger LOGGER = LoggerFactory.getLogger("parallel");

    private static final Identifier DRAFT_TEXTURE = Identifier.fromNamespaceAndPath(ParallelPlayers.MOD_ID, "textures/draft/skin.png");

    /** Imported skins already uploaded to the GPU, by character; dropped by {@link #invalidate}. */
    private static final Map<UUID, ClientAsset.ResourceTexture> CUSTOM = new HashMap<>();
    /**
     * The character skins of other players on the connected server, by player, as the server sent them. A
     * player without an entry is drawn with their account's skin. Only touched on the client thread.
     */
    private static final Map<UUID, CharacterSkin> OTHERS = new HashMap<>();
    /** The skin hashes of other players' imported skins, by player. */
    private static final Map<UUID, String> OTHER_HASHES = new HashMap<>();
    /** Other players' imported skins uploaded to the GPU, by hash; players with the same image share one. */
    private static final Map<String, ClientAsset.ResourceTexture> BY_HASH = new HashMap<>();
    /**
     * Hashes looked up in the {@link #CACHE_DIR} or asked from the server since joining, so neither is done
     * twice; an image is only taken from the server if it was asked for.
     */
    private static final Set<String> LOOKED_UP = new HashSet<>();
    /**
     * Imported skins of other players, kept across sessions and servers by {@link CharacterSkin#hash} the way
     * vanilla keeps account skins, so each image is only ever sent once.
     */
    private static final Path CACHE_DIR = FabricLoader.getInstance().getGameDir().resolve("parallel").resolve("skin_cache");

    private CharacterSkins() {
    }

    /** The skin the character should be shown with. */
    public static PlayerSkin resolve(CharacterMeta meta, PlayerSkin account) {
        CharacterSkin choice = meta.skinChoice();
        if (choice.isPreset()) {
            return preset(choice, account);
        }
        if (choice.isCustom()) {
            ClientAsset.ResourceTexture texture = customTexture(meta.id());
            if (texture != null) {
                return with(texture, choice.slim(), account);
            }
        }
        return account;
    }

    /**
     * The skin of the character played on the connected server, or {@code account} when none is. Only for
     * servers on other machines; in singleplayer it is {@link #forActiveCharacter}.
     */
    public static PlayerSkin forServerCharacter(PlayerSkin account) {
        return ClientCharacterSync.playingAs()
                .flatMap(id -> ParallelPlayers.characters().get(id))
                .map(meta -> resolve(meta, account))
                .orElse(account);
    }

    /** The skin another player is shown with: their character's, if the server said they play one. */
    public static PlayerSkin forOtherPlayer(UUID player, PlayerSkin account) {
        CharacterSkin choice = OTHERS.get(player);
        if (choice == null) {
            return account;
        }
        if (choice.isPreset()) {
            return preset(choice, account);
        }
        String hash = OTHER_HASHES.get(player);
        ClientAsset.ResourceTexture texture = hash == null ? null : sharedTexture(hash);
        // Until the image is here, they look like their account.
        return texture != null ? with(texture, choice.slim(), account) : account;
    }

    /**
     * Remembers another player's character skin, as the server sent it.
     *
     * @param skin  their character's skin, or {@code null} to show them with their account's skin again
     * @param image the {@link CharacterSkin#hash} of an imported skin's PNG
     */
    public static void setOtherPlayer(UUID player, @Nullable CharacterSkin skin, @Nullable String image) {
        OTHERS.remove(player);
        OTHER_HASHES.remove(player);
        if (skin == null || skin.isCustom() && image == null) {
            return;
        }
        OTHERS.put(player, skin);
        if (skin.isCustom()) {
            OTHER_HASHES.put(player, image);
        }
    }

    /**
     * The texture of another player's imported skin. The first time a hash is needed, it is read from the
     * {@link #CACHE_DIR}, or else asked from the server; {@code null} until it has arrived.
     */
    private static ClientAsset.@Nullable ResourceTexture sharedTexture(String hash) {
        ClientAsset.ResourceTexture loaded = BY_HASH.get(hash);
        if (loaded != null || !LOOKED_UP.add(hash)) {
            return loaded;
        }
        byte[] cached = readCached(hash);
        if (cached != null && load(hash, cached)) {
            return BY_HASH.get(hash);
        }
        if (ClientPlayNetworking.canSend(CharacterPayloads.SkinRequest.TYPE)) {
            ClientPlayNetworking.send(new CharacterPayloads.SkinRequest(hash));
        }
        return null;
    }

    /** Takes an image the server sent for a {@link CharacterPayloads.SkinRequest}, and keeps it for next time. */
    public static void receiveImage(String hash, byte[] png) {
        if (!LOOKED_UP.contains(hash) || BY_HASH.containsKey(hash) || !CharacterSkin.hash(png).equals(hash)) {
            return;
        }
        if (load(hash, png)) {
            writeCached(hash, png);
        }
    }

    /** Uploads an image of another player's skin; whether it was a valid skin image. */
    private static boolean load(String hash, byte[] png) {
        try {
            Identifier textureId = Identifier.fromNamespaceAndPath(ParallelPlayers.MOD_ID, "textures/skins/" + hash + ".png");
            register(textureId, decode(png));
            BY_HASH.put(hash, texture(textureId));
            return true;
        } catch (IOException e) {
            LOGGER.warn("Ignoring skin image {}", hash, e);
            return false;
        }
    }

    private static byte @Nullable [] readCached(String hash) {
        Path file = CACHE_DIR.resolve(hash + ".png");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            byte[] png = Files.readAllBytes(file);
            // A damaged file is fetched again, and replaced.
            return CharacterSkin.hash(png).equals(hash) ? png : null;
        } catch (IOException e) {
            LOGGER.warn("Could not read cached skin {}", hash, e);
            return null;
        }
    }

    private static void writeCached(String hash, byte[] png) {
        Path file = CACHE_DIR.resolve(hash + ".png");
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(CACHE_DIR);
            Files.write(tmp, png);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException _) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.warn("Could not cache skin {}", hash, e);
        }
    }

    /** Forgets every other player's skin; for when the client leaves a server. */
    public static void clearOtherPlayers() {
        OTHERS.clear();
        OTHER_HASHES.clear();
        LOOKED_UP.clear();
        for (ClientAsset.ResourceTexture texture : BY_HASH.values()) {
            Minecraft.getInstance().getTextureManager().release(texture.id());
        }
        BY_HASH.clear();
    }

    /** The skin of the active character for the local player, or {@code account} when none applies. */
    public static PlayerSkin forActiveCharacter(PlayerSkin account) {
        if (!ServerConfig.get().charactersEnabled) {
            return account;
        }
        return ParallelPlayers.activeCharacter().get()
                .flatMap(id -> ParallelPlayers.characters().get(id))
                .map(meta -> resolve(meta, account))
                .orElse(account);
    }

    public static PlayerSkin preset(CharacterSkin choice, PlayerSkin account) {
        String model = choice.slim() ? "slim" : "wide";
        Identifier id = Identifier.withDefaultNamespace("entity/player/" + model + "/" + choice.id());
        return with(new ClientAsset.ResourceTexture(id), choice.slim(), account);
    }

    /** Previews an image that has been imported but not saved yet, or {@code null} if there is none. */
    public static @Nullable PlayerSkin draft(boolean slim, PlayerSkin account) {
        if (Minecraft.getInstance().getTextureManager().getTexture(DRAFT_TEXTURE) instanceof DynamicTexture) {
            return with(texture(DRAFT_TEXTURE), slim, account);
        }
        return null;
    }

    /**
     * Reads and checks a skin PNG, and makes it the draft preview.
     *
     * @return {@code Optional.empty()} on success, otherwise why it was rejected
     */
    public static Optional<MutableComponent> loadDraft(byte[] png) {
        try {
            register(DRAFT_TEXTURE, decode(png));
            return Optional.empty();
        } catch (IOException _) {
            return Optional.of(CharacterSkin.imageProblem(png)
                    .map(problem -> Component.translatable(problem.translationKey()))
                    .orElseGet(() -> Component.translatable(CharacterSkin.ImageProblem.NOT_PNG.translationKey())));
        }
    }

    /** Forgets the uploaded image of a character; call after its skin file changed or was removed. */
    public static void invalidate(UUID id) {
        CUSTOM.remove(id);
        Minecraft.getInstance().getTextureManager().release(customId(id));
    }

    private static ClientAsset.@Nullable ResourceTexture customTexture(UUID id) {
        ClientAsset.ResourceTexture loaded = CUSTOM.get(id);
        if (loaded != null) {
            return loaded;
        }
        try {
            byte[] png = ParallelPlayers.characters().skinImage(id);
            if (png == null) {
                return null;
            }
            Identifier textureId = customId(id);
            register(textureId, decode(png));
            ClientAsset.ResourceTexture texture = texture(textureId);
            CUSTOM.put(id, texture);
            return texture;
        } catch (IOException | UncheckedIOException e) {
            LOGGER.warn("Could not load the skin of character {}", id, e);
            return null;
        }
    }

    /**
     * Decodes a skin PNG into a 64x64 texture image, the way vanilla prepares the skins it downloads: a legacy
     * 64x32 skin is converted, and the base layer is made opaque.
     *
     * @throws IOException if it is not a valid skin image; see {@link CharacterSkin#imageProblem}
     */
    private static NativeImage decode(byte[] png) throws IOException {
        Optional<CharacterSkin.ImageProblem> problem = CharacterSkin.imageProblem(png);
        if (problem.isPresent()) {
            throw new IOException("Not a valid skin image: " + problem.get());
        }
        NativeImage image = NativeImage.read(png);
        try {
            return SkinTextureDownloaderAccessor.parallel$processLegacySkin(image, "parallel skin");
        } catch (IllegalStateException e) {
            // Only if the decoded image disagrees with its header; vanilla has closed it already.
            throw new IOException(e);
        }
    }

    private static void register(Identifier id, NativeImage image) {
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "parallel skin", image));
    }

    private static Identifier customId(UUID id) {
        return Identifier.fromNamespaceAndPath(ParallelPlayers.MOD_ID, "textures/characters/" + id + ".png");
    }

    /** A texture whose id is also the path the renderer loads it from. */
    private static ClientAsset.ResourceTexture texture(Identifier path) {
        return new ClientAsset.ResourceTexture(path, path);
    }

    private static PlayerSkin with(ClientAsset.Texture body, boolean slim, PlayerSkin account) {
        return new PlayerSkin(body, account.cape(), account.elytra(), slim ? PlayerModelType.SLIM : PlayerModelType.WIDE, true);
    }
}
