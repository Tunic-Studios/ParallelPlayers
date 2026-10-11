package com.tunic.parallel.client.doll;

import com.google.common.collect.ImmutableMap;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.LayerDefinitions;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.world.entity.player.PlayerModelType;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.function.Function;

/**
 * The paper doll's own player renderers. They are vanilla's {@link AvatarRenderer}, so armour, held items,
 * cape and the rest work exactly as in game, but built from vanilla's model definitions directly instead of
 * the game's shared model set. Mods that change player models through that set, such as resource-pack
 * model mods, then leave the doll alone, and the doll's armour and items always line up with its body.
 *
 * <p>Made each time the game's entity renderers are (see {@code EntityRenderDispatcherMixin}), from the same
 * pieces apart from the models.
 */
public final class DollRenderers {

    private static volatile @Nullable Map<PlayerModelType, ? extends AvatarRenderer<?>> renderers;

    private DollRenderers() {
    }

    /**
     * Builds the doll's renderers, with vanilla's models in place of the ones in {@code modelSet}.
     *
     * @param makeRenderers creates avatar renderers from a model set, using the game's other pieces
     */
    public static void rebuild(Function<EntityModelSet, Map<PlayerModelType, ? extends AvatarRenderer<?>>> makeRenderers) {
        renderers = makeRenderers.apply(new VanillaModels(ImmutableMap.copyOf(LayerDefinitions.createRoots())));
    }

    /** Creates the renderers in the form {@link #rebuild} wants; see {@link EntityRenderers#createAvatarRenderers}. */
    public static Map<PlayerModelType, ? extends AvatarRenderer<?>> avatarRenderers(EntityRendererProvider.Context context) {
        return EntityRenderers.createAvatarRenderers(context);
    }

    /** The renderer for a model type, or {@code null} before the game has built its renderers. */
    public static @Nullable AvatarRenderer<?> forModel(PlayerModelType model) {
        Map<PlayerModelType, ? extends AvatarRenderer<?>> current = renderers;
        return current == null ? null : current.get(model);
    }

    /**
     * Vanilla's models, baked straight from their definitions. Overriding {@link #bakeLayer} instead of
     * calling the game's version is what keeps other mods' changes to that method out of the doll.
     */
    private static final class VanillaModels extends EntityModelSet {

        private final Map<ModelLayerLocation, LayerDefinition> roots;

        VanillaModels(Map<ModelLayerLocation, LayerDefinition> roots) {
            super(roots);
            this.roots = roots;
        }

        @Override
        public @NonNull ModelPart bakeLayer(@NonNull ModelLayerLocation id) {
            LayerDefinition definition = this.roots.get(id);
            if (definition == null) {
                throw new IllegalArgumentException("No model for layer " + id);
            }
            return definition.bakeRoot();
        }
    }
}
