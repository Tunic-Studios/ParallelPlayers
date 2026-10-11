package com.tunic.parallel.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.tunic.parallel.client.doll.DollRenderers;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.MapRenderer;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.resources.model.EquipmentAssetManager;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import net.minecraft.client.resources.palette.PalettedTextureManager;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

    /**
     * Whenever the game builds its entity renderers, also builds the paper doll's, from the same pieces but
     * with vanilla's models (see {@link DollRenderers}).
     */
    @WrapOperation(
            method = "onResourceManagerReload",
            at = @At(value = "NEW", target = "net/minecraft/client/renderer/entity/EntityRendererProvider$Context")
    )
    private EntityRendererProvider.Context parallel$buildDollRenderers(
            EntityRenderDispatcher entityRenderDispatcher, BlockModelResolver blockModelResolver, ItemModelResolver itemModelResolver,
            MapRenderer mapRenderer, ResourceManager resourceManager, EntityModelSet modelSet, EquipmentAssetManager equipmentAssets,
            AtlasManager atlasManager, Font font, PlayerSkinRenderCache playerSkinRenderCache, PalettedTextureManager palettedTextures,
            Operation<EntityRendererProvider.Context> original) {
        DollRenderers.rebuild(vanillaModels -> DollRenderers.avatarRenderers(original.call(entityRenderDispatcher, blockModelResolver,
                itemModelResolver, mapRenderer, resourceManager, vanillaModels, equipmentAssets, atlasManager, font, playerSkinRenderCache, palettedTextures)));
        return original.call(entityRenderDispatcher, blockModelResolver, itemModelResolver, mapRenderer, resourceManager, modelSet, equipmentAssets, atlasManager, font, playerSkinRenderCache, palettedTextures);
    }
}
