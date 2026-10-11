package com.tunic.parallel.client.doll;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3fc;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Draws the paper doll into the GUI with {@link DollRenderers} instead of the game's own player renderers.
 * Otherwise the same as vanilla's GUI entity drawing.
 */
public class DollPictureRenderer extends PictureInPictureRenderer<DollPictureRenderer.State> {

    /** Light level for a fully lit entity, as vanilla's GUI entities use. */
    private static final int FULL_BRIGHT = 15728880;

    /**
     * Adds the doll to the frame, the way {@link GuiGraphicsExtractor#entity} adds an entity, or with that
     * method itself before the doll's renderers exist.
     */
    public static void extract(GuiGraphicsExtractor graphics, AvatarRenderState state, float scale, Vector3fc translation,
                               Quaternionfc rotation, @Nullable Quaternionfc overrideCameraAngle,
                               int x0, int y0, int x1, int y1) {
        if (DollRenderers.forModel(state.skin.model()) == null) {
            graphics.entity(state, scale, translation, rotation, overrideCameraAngle, x0, y0, x1, y1);
            return;
        }
        state.lightCoords = FULL_BRIGHT;
        graphics.guiRenderState.addPicturesInPictureState(new State(state, translation, rotation, overrideCameraAngle,
                x0, y0, x1, y1, scale, graphics.scissorStack.peek()));
    }

    @Override
    public @NonNull Class<State> getRenderStateClass() {
        return State.class;
    }

    @Override
    protected void renderToTexture(State doll, @NonNull PoseStack poseStack, @NonNull SubmitNodeCollector submitNodeCollector) {
        AvatarRenderer<?> renderer = DollRenderers.forModel(doll.renderState().skin.model());
        if (renderer == null) {
            return;
        }
        Minecraft.getInstance().gameRenderer.lighting().setupFor(Lighting.Entry.ENTITY_IN_UI);
        poseStack.translate(doll.translation().x(), doll.translation().y(), doll.translation().z());
        poseStack.rotate(doll.rotation());
        CameraRenderState camera = new CameraRenderState();
        if (doll.overrideCameraAngle() != null) {
            camera.orientation = doll.overrideCameraAngle().conjugate(new Quaternionf()).rotateY((float) Math.PI);
        }
        renderer.submit(doll.renderState(), poseStack, submitNodeCollector, camera);
    }

    @Override
    protected float getTranslateY(int height, int guiScale) {
        return height / 2.0F;
    }

    @Override
    protected @NonNull String getTextureLabel() {
        return "parallel doll";
    }

    /** The doll to draw and where; the same as vanilla's state for a GUI entity. */
    public record State(AvatarRenderState renderState, Vector3fc translation, Quaternionfc rotation,
                        @Nullable Quaternionfc overrideCameraAngle, int x0, int y0, int x1, int y1, float scale,
                        @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds)
            implements PictureInPictureRenderState {

        State(AvatarRenderState renderState, Vector3fc translation, Quaternionfc rotation,
              @Nullable Quaternionfc overrideCameraAngle, int x0, int y0, int x1, int y1, float scale,
              @Nullable ScreenRectangle scissorArea) {
            this(renderState, translation, rotation, overrideCameraAngle, x0, y0, x1, y1, scale, scissorArea,
                    PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissorArea));
        }
    }
}
