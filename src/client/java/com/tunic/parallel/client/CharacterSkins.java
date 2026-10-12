package com.tunic.parallel.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.tunic.parallel.ParallelPlayers;
import com.tunic.parallel.config.ServerConfig;
import com.tunic.parallel.character.CharacterMeta;
import com.tunic.parallel.character.CharacterSkin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import com.tunic.parallel.mixin.client.SkinTextureDownloaderAccessor;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
    private static final Map<UUID, OtherSkin> OTHERS = new HashMap<>();

    /** Another player's character skin; {@code texture} is set for an imported one. */
    private record OtherSkin(CharacterSkin choice, ClientAsset.@Nullable ResourceTexture texture) {
    }

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
        OtherSkin other = OTHERS.get(player);
        if (other == null) {
            return account;
        }
        if (other.texture() != null) {
            return with(other.texture(), other.choice().slim(), account);
        }
        return other.choice().isPreset() ? preset(other.choice(), account) : account;
    }

    /**
     * Remembers another player's character skin, as the server sent it.
     *
     * @param skin  their character's skin, or {@code null} to show them with their account's skin again
     * @param image the PNG of an imported skin
     */
    public static void setOtherPlayer(UUID player, @Nullable CharacterSkin skin, byte @Nullable [] image) {
        forgetOtherPlayer(player);
        if (skin == null) {
            return;
        }
        if (!skin.isCustom()) {
            OTHERS.put(player, new OtherSkin(skin, null));
            return;
        }
        if (image == null) {
            return;
        }
        try {
            Identifier textureId = otherPlayerId(player);
            register(textureId, decode(image));
            OTHERS.put(player, new OtherSkin(skin, texture(textureId)));
        } catch (IOException e) {
            LOGGER.warn("Ignoring the skin the server sent for player {}", player, e);
        }
    }

    /** Forgets every other player's skin; for when the client leaves a server. */
    public static void clearOtherPlayers() {
        for (UUID player : List.copyOf(OTHERS.keySet())) {
            forgetOtherPlayer(player);
        }
    }

    private static void forgetOtherPlayer(UUID player) {
        OtherSkin previous = OTHERS.remove(player);
        if (previous != null && previous.texture() != null) {
            Minecraft.getInstance().getTextureManager().release(otherPlayerId(player));
        }
    }

    private static Identifier otherPlayerId(UUID player) {
        return Identifier.fromNamespaceAndPath(ParallelPlayers.MOD_ID, "textures/players/" + player + ".png");
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
