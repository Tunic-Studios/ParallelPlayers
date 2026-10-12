package com.tunic.parallel.client;

import com.tunic.parallel.ParallelPlayers;
import com.tunic.parallel.client.config.ClientConfig;
import com.tunic.parallel.client.doll.DollPictureRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
import net.minecraft.client.Minecraft;

public class ParallelPlayersClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ParallelPlayers.setAccount(() -> Minecraft.getInstance().getUser().getProfileId());
        ClientConfig.reload();
        ClientCharacterSync.register();
        PictureInPictureRendererRegistry.register(_ -> new DollPictureRenderer());
    }
}
