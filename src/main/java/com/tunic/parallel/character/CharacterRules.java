package com.tunic.parallel.character;

import com.tunic.parallel.ParallelPlayers;
import com.tunic.parallel.config.ParallelPermissions;
import com.tunic.parallel.config.ServerConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.WorldData;

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
        return check(world, ParallelPlayers.activeCharacter().get());
    }

    /** {@link #check(CharacterMode)} for a given character; empty means the account, which may go anywhere. */
    private static Optional<Refusal> check(CharacterMode world, Optional<UUID> active) {
        ServerConfig config = ServerConfig.get();
        if (active.isEmpty() || !config.charactersEnabled || !config.enforceModes) {
            return Optional.empty();
        }
        CharacterStore store = ParallelPlayers.characters();
        CharacterMeta character = store.get(active.get()).orElse(null);
        if (character == null) {
            return Optional.empty();
        }
        CharacterMode mode = character.modeChoice();
        if (mode == null || mode.canJoin(world)) {
            return Optional.empty();
        }

        List<CharacterMeta> fitting = store.list().stream()
                .filter(other -> {
                    CharacterMode otherMode = other.modeChoice();
                    return otherMode != null && otherMode.canJoin(world);
                })
                .toList();
        Component modeName = Component.translatable(mode.translationKey());
        Component worldName = Component.translatable(world.translationKey());
        Component suggestion = fitting.isEmpty()
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
        Optional<UUID> character = CharacterSwap.playingAs(server, player.nameAndId());
        if (character.isEmpty()) {
            return Optional.empty();
        }
        WorldData data = server.getWorldData();
        CharacterMode world = CharacterMode.ofWorld(data.isHardcore(), data.getGameType(), data.isAllowCommands());
        Optional<Refusal> refusal = check(world, character);
        if (refusal.isPresent() && !ParallelPermissions.bypassesModes(player)) {
            return refusal;
        }
        CharacterStore store = ParallelPlayers.characters();
        character
                .flatMap(store::get)
                .filter(meta -> meta.modeChoice() == null)
                .ifPresent(meta -> store.setMode(meta.id(), world));
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
