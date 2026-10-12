package com.tunic.parallel.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.tunic.parallel.ParallelPlayers;
import com.tunic.parallel.character.CharacterNames;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Asks Mojang whether a character name would be allowed as a Minecraft username, which is where its filter
 * for inappropriate names applies. Uses the name-availability check the launcher makes when changing a
 * username, with the signed-in account's token, which is only ever sent to Mojang.
 *
 * <p>When the check cannot be made (an offline account, no connection, Mojang unavailable) the name is let
 * through: only the format rules in {@link CharacterNames} apply then.
 */
public final class MojangNameCheck {

    private static final Logger LOGGER = LoggerFactory.getLogger(ParallelPlayers.MOD_ID);
    private static final String URL = "https://api.minecraftservices.com/minecraft/profile/name/%s/available";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    public enum Result {
        ALLOWED,
        NOT_ALLOWED,
        /** Mojang could not be asked; the name is treated as allowed. */
        UNKNOWN
    }

    private MojangNameCheck() {
    }

    /** Checks a name that already follows {@link CharacterNames}' rules. Completes off the client thread. */
    public static CompletableFuture<Result> check(String name) {
        String token = Minecraft.getInstance().getUser().getAccessToken();
        List<CompletableFuture<Result>> checks = CharacterNames.usernamesToCheck(name).stream()
                .map(username -> checkUsername(username, token))
                .toList();
        return CompletableFuture.allOf(checks.toArray(CompletableFuture[]::new)).thenApply(_ -> {
            List<Result> results = checks.stream().map(CompletableFuture::join).toList();
            if (results.contains(Result.NOT_ALLOWED)) {
                return Result.NOT_ALLOWED;
            }
            return results.contains(Result.UNKNOWN) ? Result.UNKNOWN : Result.ALLOWED;
        });
    }

    private static CompletableFuture<Result> checkUsername(String username, String token) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(URL.formatted(URLEncoder.encode(username, StandardCharsets.UTF_8))))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        LOGGER.info("Mojang's name check answered {}; only the format rules apply", response.statusCode());
                        return Result.UNKNOWN;
                    }
                    // "AVAILABLE" or "DUPLICATE" (someone has it as a username) are both fine for a character.
                    JsonElement status = JsonParser.parseString(response.body()).getAsJsonObject().get("status");
                    return status != null && "NOT_ALLOWED".equals(status.getAsString()) ? Result.NOT_ALLOWED : Result.ALLOWED;
                })
                .exceptionally(e -> {
                    LOGGER.info("Could not reach Mojang's name check; only the format rules apply", e);
                    return Result.UNKNOWN;
                });
    }
}
