package com.tunic.parallel.character;

import com.tunic.parallel.ParallelPlayers;
import com.tunic.parallel.config.ParallelPermissions;
import com.tunic.parallel.config.ServerConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.WorldData;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Decides whether the active character may enter a world, and explains why not. */
public final class CharacterRules {

    private CharacterRules() {
    }

    /**
     * Checks the active character against a world.
     *
     * @param world the kind of world about to be entered
     * @return the reason the join must be refused, or empty if it is fine (including when no character is
     * active, or the active one has no mode yet and will take the world's)
     */
    public static Optional<Refusal> check(CharacterMode world) {
        return check(world, ParallelPlayers.characters(), ParallelPlayers.activeCharacter().get().orElse(null), true);
    }

    /**
     * {@link #check(CharacterMode)} for a given character; {@code null} is the account, which may go anywhere.
     *
     * @param suggest whether to name the other characters in {@code storage} that could join instead. Only for
     *                the client's own storage: a server's copies are just the characters played there
     */
    private static Optional<Refusal> check(CharacterMode world, CharacterStorage storage, @Nullable UUID active, boolean suggest) {
        ServerConfig config = ServerConfig.get();
        if (active == null || !config.charactersEnabled || !config.enforceModes) {
            return Optional.empty();
        }
        CharacterMeta character = storage.get(active).orElse(null);
        if (character == null) {
            return Optional.empty();
        }
        CharacterMode mode = character.modeChoice();
        if (mode == null || mode.canJoin(world)) {
            return Optional.empty();
        }

        List<CharacterMeta> fitting = !suggest ? List.of() : storage.list().stream()
                .filter(other -> {
                    CharacterMode otherMode = other.modeChoice();
                    return otherMode != null && otherMode.canJoin(world);
                })
                .toList();
        Component modeName = Component.translatable(mode.translationKey());
        Component worldName = Component.translatable(world.translationKey());
        Component suggestion = !suggest
                ? Component.translatable("parallel.mode.blocked.server", worldName)
                : fitting.isEmpty()
                ? Component.translatable("parallel.mode.blocked.none", worldName)
                : Component.translatable("parallel.mode.blocked.suggest",
                        fitting.stream().map(CharacterMeta::name).collect(java.util.stream.Collectors.joining(", ")));
        Component message = Component.translatable("parallel.mode.blocked.message", character.name(), modeName, worldName)
                .append("\n\n").append(suggestion);
        return Optional.of(new Refusal(Component.translatable("parallel.mode.blocked.title"), message,
                character, mode, world, fitting));
    }

    /**
     * Whether the active character can be used in a world of the given kind: one is active, and it either
     * fits or has no mode yet (and will adopt the world's).
     */
    public static boolean hasUsableCharacter(CharacterMode world) {
        return ParallelPlayers.activeCharacter().get().isPresent() && check(world).isEmpty();
    }

    /**
     * Runs when a player has joined a world: refuses a mismatched character (the safety net for entry
     * points the menus do not cover, such as creating a new world), unless the player may bypass the
     * rule, and otherwise gives a character that has no mode yet the world's mode.
     */
    public static Optional<Refusal> onJoin(MinecraftServer server, ServerPlayer player) {
        CharacterSwap.Session session = CharacterSwap.session(player.nameAndId()).orElse(null);
        if (session == null) {
            return Optional.empty();
        }
        WorldData data = server.getWorldData();
        CharacterMode world = CharacterMode.ofWorld(data.isHardcore(), data.getGameType(), data.isAllowCommands());
        Optional<Refusal> refusal = check(world, session.storage(), session.character(), !session.remote());
        if (refusal.isPresent() && !ParallelPermissions.bypassesModes(player)) {
            return refusal;
        }
        session.meta()
                .filter(meta -> meta.modeChoice() == null)
                .ifPresent(meta -> session.storage().setMode(meta.id(), world));
        return Optional.empty();
    }

    /**
     * Why a join was refused.
     *
     * @param message       the full explanation, including which characters could join
     * @param character     the character that was refused
     * @param characterMode that character's mode
     * @param world         the kind of world it tried to enter
     * @param fitting       the characters that could join instead, most recently played first
     */
    public record Refusal(Component title, Component message, CharacterMeta character, CharacterMode characterMode,
                          CharacterMode world, List<CharacterMeta> fitting) {
    }
}
