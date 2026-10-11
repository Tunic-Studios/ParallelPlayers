package com.tunic.parallel.client;

import com.tunic.parallel.client.config.ClientConfig;
import net.fabricmc.api.ClientModInitializer;

public class ParallelPlayersClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientConfig.reload();
        ClientCharacterSync.register();
    }
}
