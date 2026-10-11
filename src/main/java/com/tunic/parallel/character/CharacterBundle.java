package com.tunic.parallel.character;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.jspecify.annotations.Nullable;

/**
 * Everything about a character that travels between a client and a server: its metadata, player data,
 * statistics, advancements and menu appearance. The skin image stays on the client, which is the only side
 * that draws it.
 *
 * @param playerData   the character's {@code playerdata.dat}, or {@code null} if it has never been played
 * @param appearance   its {@code appearance.dat}, or {@code null}
 * @param stats        its statistics JSON, or {@code null}
 * @param advancements its advancements JSON, or {@code null}
 */
public record CharacterBundle(CharacterMeta meta, byte @Nullable [] playerData, byte @Nullable [] appearance,
                             @Nullable String stats, @Nullable String advancements) {

    /** Largest single file accepted in a bundle; a character's player data is normally far smaller. */
    public static final int MAX_FILE_SIZE = 8 * 1024 * 1024;
    /** Largest whole bundle on the wire, for the payloads that carry one. */
    public static final int MAX_SIZE = 4 * MAX_FILE_SIZE + 64 * 1024;

    public static final StreamCodec<FriendlyByteBuf, CharacterBundle> STREAM_CODEC = StreamCodec.of(
            (buf, bundle) -> {
                buf.writeUtf(CharacterStorage.GSON.toJson(bundle.meta()), MAX_FILE_SIZE);
                buf.writeNullable(bundle.playerData(), (out, bytes) -> out.writeByteArray(bytes));
                buf.writeNullable(bundle.appearance(), (out, bytes) -> out.writeByteArray(bytes));
                buf.writeNullable(bundle.stats(), (out, text) -> out.writeUtf(text, MAX_FILE_SIZE));
                buf.writeNullable(bundle.advancements(), (out, text) -> out.writeUtf(text, MAX_FILE_SIZE));
            },
            buf -> new CharacterBundle(
                    CharacterStorage.GSON.fromJson(buf.readUtf(MAX_FILE_SIZE), CharacterMeta.class),
                    buf.readNullable(in -> in.readByteArray(MAX_FILE_SIZE)),
                    buf.readNullable(in -> in.readByteArray(MAX_FILE_SIZE)),
                    buf.readNullable(in -> in.readUtf(MAX_FILE_SIZE)),
                    buf.readNullable(in -> in.readUtf(MAX_FILE_SIZE))));
}
