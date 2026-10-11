package com.tunic.parallel_players.character;

import com.tunic.parallel_players.ParallelPlayers;
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
        CharacterStore store = ParallelPlayers.characters();
        Optional<UUID> active = ParallelPlayers.activeCharacter().get();
        if (active.isEmpty()) {
            return Optional.empty();
        }
        CharacterMeta character = store.get(active.get()).orElse(null);
        if (character == null) {
            return Optional.empty();
        }
        CharacterMode mode = character.modeChoice();
        if (mode == null || mode.canJoin(world)) {
            return Optional.empty();
        }

        List<String> fitting = store.list().stream()
                .filter(other -> {
                    CharacterMode otherMode = other.modeChoice();
                    return otherMode != null && otherMode.canJoin(world);
                })
                .map(CharacterMeta::name)
                .toList();
        Component modeName = Component.translatable(mode.translationKey());
        Component worldName = Component.translatable(world.translationKey());
        Component suggestion = fitting.isEmpty()
                ? Component.translatable("parallelplayers.mode.blocked.none", worldName)
                : Component.translatable("parallelplayers.mode.blocked.suggest", String.join(", ", fitting));
        Component message = Component.translatable("parallelplayers.mode.blocked.message", character.name(), modeName, worldName)
                .append("\n\n").append(suggestion);
        return Optional.of(new Refusal(Component.translatable("parallelplayers.mode.blocked.title"), message));
    }

    /**
     * Whether the active character can be used in a world of the given kind: one is active, and it either
     * fits or has no mode yet (and will adopt the world's).
     */
    public static boolean hasUsableCharacter(CharacterMode world) {
        return ParallelPlayers.activeCharacter().get().isPresent() && check(world).isEmpty();
    }

    /**
     * Runs when the singleplayer owner has joined a world: refuses a mismatched character (the safety net
     * for entry points the menus do not cover, such as creating a new world), and otherwise gives a
     * character that has no mode yet the world's mode.
     */
    public static Optional<Refusal> onJoin(MinecraftServer server, ServerPlayer player) {
        if (!server.isSingleplayerOwner(player.nameAndId())) {
            return Optional.empty();
        }
        WorldData data = server.getWorldData();
        CharacterMode world = CharacterMode.ofWorld(data.isHardcore(), data.getGameType(), data.isAllowCommands());
        Optional<Refusal> refusal = check(world);
        if (refusal.isPresent()) {
            return refusal;
        }
        CharacterStore store = ParallelPlayers.characters();
        ParallelPlayers.activeCharacter().get()
                .flatMap(store::get)
                .filter(character -> character.modeChoice() == null)
                .ifPresent(character -> store.setMode(character.id(), world));
        return Optional.empty();
    }

    /** Why a join was refused. */
    public record Refusal(Component title, Component message) {
    }
}
