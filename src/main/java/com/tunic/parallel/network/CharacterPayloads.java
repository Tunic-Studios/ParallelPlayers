package com.tunic.parallel.network;

import com.tunic.parallel.ParallelPlayers;
import com.tunic.parallel.character.CharacterBundle;
import com.tunic.parallel.character.CharacterGamemode;
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
 * <p>The server also tells every client with the mod who each player is playing as ({@link PlayerCharacters}),
 * so characters look like themselves and carry their own name to everyone.
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
        PayloadTypeRegistry.clientboundPlay().registerLarge(PlayerCharacters.TYPE, PlayerCharacters.STREAM_CODEC, PlayerCharacters.MAX_SIZE);
        PayloadTypeRegistry.clientboundPlay().register(ShowUsernames.TYPE, ShowUsernames.STREAM_CODEC);
    }

    /**
     * Raise whenever any of these messages changes. It is part of every channel name, so a client and a server
     * with different protocols do not see each other's channels at all: the player joins as their account,
     * as with a server without the mod, instead of being disconnected by a message the other cannot read.
     */
    private static final int PROTOCOL = 1;

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String name) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(ParallelPlayers.MOD_ID, "v" + PROTOCOL + "/" + name));
    }

    /**
     * Server to client, while connecting: which character will you play as?
     *
     * @param worldGamemode the gamemode a character needs to join, or {@code null} when any character may
     *                      (gamemode rules are off, or the player may bypass them)
     * @param worldId       identifies the server's world, for the client to remember what it synced there
     */
    public record Request(@Nullable CharacterGamemode worldGamemode, String worldId) implements CustomPacketPayload {

        public static final Type<Request> TYPE = payloadType("request_character");
        public static final StreamCodec<FriendlyByteBuf, Request> STREAM_CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeNullable(payload.worldGamemode(), CharacterGamemode.STREAM_CODEC);
                    buf.writeUtf(payload.worldId(), 64);
                },
                buf -> new Request(buf.readNullable(CharacterGamemode.STREAM_CODEC), buf.readUtf(64)));

        @Override
        public @NonNull Type<Request> type() {
            return TYPE;
        }
    }

    /**
     * Client to server, while connecting: the active character, or {@code null} to play as the account.
     *
     * @param synced the save of the character last synced with this server, or {@code null} if never
     */
    public record Upload(@Nullable CharacterBundle character, @Nullable Long synced) implements CustomPacketPayload {

        public static final Type<Upload> TYPE = payloadType("upload_character");
        public static final StreamCodec<FriendlyByteBuf, Upload> STREAM_CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeNullable(payload.character(), CharacterBundle.STREAM_CODEC);
                    buf.writeNullable(payload.synced(), FriendlyByteBuf::writeLong);
                },
                buf -> new Upload(buf.readNullable(CharacterBundle.STREAM_CODEC), buf.readNullable(FriendlyByteBuf::readLong)));

        @Override
        public @NonNull Type<Upload> type() {
            return TYPE;
        }
    }

    /**
     * Server to client, while connecting: the character the player plays as ({@code null} for the account),
     * and the server's copy of it when the client must take that over its own.
     *
     * @param saveId   the save client and server now agree on
     * @param conflict whether the client's copy had changed too; it keeps that copy as a backup
     */
    public record Result(@Nullable UUID playingAs, @Nullable CharacterBundle newer, long saveId, boolean conflict)
            implements CustomPacketPayload {

        public static final Type<Result> TYPE = payloadType("character_result");
        public static final StreamCodec<FriendlyByteBuf, Result> STREAM_CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeNullable(payload.playingAs(), UUIDUtil.STREAM_CODEC);
                    buf.writeNullable(payload.newer(), CharacterBundle.STREAM_CODEC);
                    buf.writeLong(payload.saveId());
                    buf.writeBoolean(payload.conflict());
                },
                buf -> new Result(buf.readNullable(UUIDUtil.STREAM_CODEC), buf.readNullable(CharacterBundle.STREAM_CODEC),
                        buf.readLong(), buf.readBoolean()));

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
     * Server to client, while playing: who players are playing as, so clients can show each player's character
     * by name (above their head) and by skin.
     *
     * @param players one entry per player
     */
    public record PlayerCharacters(List<Entry> players) implements CustomPacketPayload {

        /** Enough for a full server of imported skins. */
        public static final int MAX_SIZE = 64 * (CharacterSkin.MAX_IMAGE_BYTES + 1024);
        public static final Type<PlayerCharacters> TYPE = payloadType("player_characters");
        public static final StreamCodec<FriendlyByteBuf, PlayerCharacters> STREAM_CODEC = Entry.STREAM_CODEC
                .apply(ByteBufCodecs.list())
                .map(PlayerCharacters::new, PlayerCharacters::players);

        @Override
        public @NonNull Type<PlayerCharacters> type() {
            return TYPE;
        }

        /**
         * @param name  the character's name, or {@code null} when the player plays as their account
         * @param skin  the character's skin, or {@code null} for the account's own
         * @param image the PNG for an imported skin ({@link CharacterSkin#CUSTOM}), otherwise {@code null}
         */
        public record Entry(UUID player, @Nullable String name, @Nullable CharacterSkin skin, byte @Nullable [] image) {

            public static final StreamCodec<FriendlyByteBuf, Entry> STREAM_CODEC = StreamCodec.of(
                    (buf, entry) -> {
                        buf.writeUUID(entry.player());
                        buf.writeNullable(entry.name(), FriendlyByteBuf::writeUtf);
                        buf.writeNullable(entry.skin(), (out, skin) -> {
                            out.writeNullable(skin.id(), FriendlyByteBuf::writeUtf);
                            out.writeBoolean(skin.slim());
                        });
                        buf.writeNullable(entry.image(), (out, bytes) -> out.writeByteArray(bytes));
                    },
                    buf -> new Entry(
                            buf.readUUID(),
                            buf.readNullable(FriendlyByteBuf::readUtf),
                            buf.readNullable(in -> new CharacterSkin(in.readNullable(FriendlyByteBuf::readUtf), in.readBoolean())),
                            buf.readNullable(in -> in.readByteArray(CharacterSkin.MAX_IMAGE_BYTES))));
        }
    }

    /**
     * Server to client, while playing: whether this player may see the usernames of players on characters.
     * Sent when they join and whenever they are made operator or stop being one.
     */
    public record ShowUsernames(boolean show) implements CustomPacketPayload {

        public static final Type<ShowUsernames> TYPE = payloadType("show_usernames");
        public static final StreamCodec<FriendlyByteBuf, ShowUsernames> STREAM_CODEC = StreamCodec.of(
                (buf, payload) -> buf.writeBoolean(payload.show()),
                buf -> new ShowUsernames(buf.readBoolean()));

        @Override
        public @NonNull Type<ShowUsernames> type() {
            return TYPE;
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
