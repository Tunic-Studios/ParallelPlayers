package com.tunic.parallel.network;

import com.tunic.parallel.ParallelPlayers;
import com.tunic.parallel.character.CharacterBundle;
import com.tunic.parallel.character.CharacterSkin;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * The messages that carry characters between client and server. While a player connects:
 * <ol>
 *   <li>the server asks for their character, saying what kind of world it is ({@link Request}),</li>
 *   <li>the client sends its active character, or nothing to play as the account ({@link Upload}); if the
 *       active character cannot join that kind of world, the player picks another one first,</li>
 *   <li>the server says which character the player will play as, with its own copy if that is newer
 *       ({@link Result}).</li>
 * </ol>
 * Then, while they play, the server sends the character after every save ({@link Sync}). When they leave,
 * the client asks for one last save ({@link Leave}) and disconnects once it has it ({@link LeaveReady}):
 * the save vanilla makes after the connection is gone could not reach the client any more.
 *
 * <p>The server also tells every client with the mod which skin each player's character has
 * ({@link PlayerSkins}), so characters look like themselves to everyone.
 */
public final class CharacterPayloads {

    private CharacterPayloads() {
    }

    public static void register() {
        PayloadTypeRegistry.clientboundConfiguration().register(Request.TYPE, Request.STREAM_CODEC);
        PayloadTypeRegistry.serverboundConfiguration().registerLarge(Upload.TYPE, Upload.STREAM_CODEC, CharacterBundle.MAX_SIZE);
        PayloadTypeRegistry.clientboundConfiguration().registerLarge(Result.TYPE, Result.STREAM_CODEC, CharacterBundle.MAX_SIZE);
        PayloadTypeRegistry.clientboundPlay().registerLarge(Sync.TYPE, Sync.STREAM_CODEC, CharacterBundle.MAX_SIZE);
        PayloadTypeRegistry.serverboundPlay().register(Leave.TYPE, Leave.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(LeaveReady.TYPE, LeaveReady.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().registerLarge(PlayerSkins.TYPE, PlayerSkins.STREAM_CODEC, PlayerSkins.MAX_SIZE);
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String name) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(ParallelPlayers.MOD_ID, name));
    }

    /**
     * Server to client, while connecting: which character will you play as?
     *
     * @param worldMode the id of the {@link com.tunic.parallel.character.CharacterMode} a character needs to join,
     *                  or {@code null} when any character may (mode rules are off, or the player may bypass them)
     */
    public record Request(@Nullable String worldMode) implements CustomPacketPayload {

        public static final Type<Request> TYPE = payloadType("request_character");
        public static final StreamCodec<FriendlyByteBuf, Request> STREAM_CODEC = StreamCodec.of(
                (buf, payload) -> buf.writeNullable(payload.worldMode(), FriendlyByteBuf::writeUtf),
                buf -> new Request(buf.readNullable(FriendlyByteBuf::readUtf)));

        @Override
        public @NonNull Type<Request> type() {
            return TYPE;
        }
    }

    /** Client to server, while connecting: the active character, or {@code null} to play as the account. */
    public record Upload(@Nullable CharacterBundle character) implements CustomPacketPayload {

        public static final Type<Upload> TYPE = payloadType("upload_character");
        public static final StreamCodec<FriendlyByteBuf, Upload> STREAM_CODEC = StreamCodec.of(
                (buf, payload) -> buf.writeNullable(payload.character(), CharacterBundle.STREAM_CODEC),
                buf -> new Upload(buf.readNullable(CharacterBundle.STREAM_CODEC)));

        @Override
        public @NonNull Type<Upload> type() {
            return TYPE;
        }
    }

    /**
     * Server to client, while connecting: the character the player plays as ({@code null} for the account),
     * and the server's copy of it when that is newer than the one the client sent.
     */
    public record Result(@Nullable UUID playingAs, @Nullable CharacterBundle newer) implements CustomPacketPayload {

        public static final Type<Result> TYPE = payloadType("character_result");
        public static final StreamCodec<FriendlyByteBuf, Result> STREAM_CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeNullable(payload.playingAs(), UUIDUtil.STREAM_CODEC);
                    buf.writeNullable(payload.newer(), CharacterBundle.STREAM_CODEC);
                },
                buf -> new Result(buf.readNullable(UUIDUtil.STREAM_CODEC), buf.readNullable(CharacterBundle.STREAM_CODEC)));

        @Override
        public @NonNull Type<Result> type() {
            return TYPE;
        }
    }

    /** Client to server, when the player chooses to leave: save my character and send it to me now. */
    public record Leave() implements CustomPacketPayload {

        public static final Type<Leave> TYPE = payloadType("leave");
        public static final StreamCodec<FriendlyByteBuf, Leave> STREAM_CODEC = StreamCodec.unit(new Leave());

        @Override
        public @NonNull Type<Leave> type() {
            return TYPE;
        }
    }

    /** Server to client, after the last {@link Sync} a {@link Leave} asked for: the client may disconnect now. */
    public record LeaveReady() implements CustomPacketPayload {

        public static final Type<LeaveReady> TYPE = payloadType("leave_ready");
        public static final StreamCodec<FriendlyByteBuf, LeaveReady> STREAM_CODEC = StreamCodec.unit(new LeaveReady());

        @Override
        public @NonNull Type<LeaveReady> type() {
            return TYPE;
        }
    }

    /**
     * Server to client, while playing: the skins of players' characters.
     *
     * @param skins one entry per player; a player whose entry has no skin is shown with their account's skin
     */
    public record PlayerSkins(List<Entry> skins) implements CustomPacketPayload {

        /** Enough for a full server of imported skins. */
        public static final int MAX_SIZE = 64 * (CharacterBundle.MAX_SKIN_SIZE + 1024);
        public static final Type<PlayerSkins> TYPE = payloadType("player_skins");
        public static final StreamCodec<FriendlyByteBuf, PlayerSkins> STREAM_CODEC = Entry.STREAM_CODEC
                .apply(ByteBufCodecs.list())
                .map(PlayerSkins::new, PlayerSkins::skins);

        @Override
        public @NonNull Type<PlayerSkins> type() {
            return TYPE;
        }

        /**
         * @param skin  the character's skin, or {@code null} for the account's own
         * @param image the PNG for an imported skin ({@link CharacterSkin#CUSTOM}), otherwise {@code null}
         */
        public record Entry(UUID player, @Nullable CharacterSkin skin, byte @Nullable [] image) {

            public static final StreamCodec<FriendlyByteBuf, Entry> STREAM_CODEC = StreamCodec.of(
                    (buf, entry) -> {
                        buf.writeUUID(entry.player());
                        buf.writeNullable(entry.skin(), (out, skin) -> {
                            out.writeNullable(skin.id(), FriendlyByteBuf::writeUtf);
                            out.writeBoolean(skin.slim());
                        });
                        buf.writeNullable(entry.image(), (out, bytes) -> out.writeByteArray(bytes));
                    },
                    buf -> new Entry(
                            buf.readUUID(),
                            buf.readNullable(in -> new CharacterSkin(in.readNullable(FriendlyByteBuf::readUtf), in.readBoolean())),
                            buf.readNullable(in -> in.readByteArray(CharacterBundle.MAX_SKIN_SIZE))));
        }
    }

    /** Server to client, while playing: the character as it was just saved. */
    public record Sync(CharacterBundle character) implements CustomPacketPayload {

        public static final Type<Sync> TYPE = payloadType("sync_character");
        public static final StreamCodec<FriendlyByteBuf, Sync> STREAM_CODEC =
                CharacterBundle.STREAM_CODEC.map(Sync::new, Sync::character);

        @Override
        public @NonNull Type<Sync> type() {
            return TYPE;
        }
    }
}
