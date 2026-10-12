package com.tunic.parallel.mixin.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SkinTextureDownloader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(SkinTextureDownloader.class)
public interface SkinTextureDownloaderAccessor {

    /**
     * Vanilla's cleanup of a downloaded skin: turns a legacy 64x32 skin into a 64x64 one, and makes the base
     * layer opaque. Closes {@code image} and returns a new one for a legacy skin.
     *
     * @throws IllegalStateException if the image is neither 64x64 nor 64x32; {@code image} is closed then
     */
    @Invoker("processLegacySkin")
    static NativeImage parallel$processLegacySkin(NativeImage image, String url) {
        throw new AssertionError();
    }
}
