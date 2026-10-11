package com.tunic.parallel.client.screen;

import com.tunic.parallel.character.CharacterLoadout;
import com.tunic.parallel.client.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AbstractSkullBlock;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.function.Supplier;

/**
 * A character drawn as a full player model: skin, worn armor, held items and cape. Unlike the vanilla
 * skin widget this renders through an avatar render state, which is what the armor, held item and
 * cape layers read from, and it needs no world.
 */
public class CharacterDollWidget extends AbstractWidget {

    private static final float ROTATION_SENSITIVITY = 2.5F;
    private static final float PLAYER_HEIGHT = 1.8F;
    /** How many blocks of height the widget shows, so the player fits with some air around it. */
    private static final float VIEW_HEIGHT_BLOCKS = 2.2F;
    /** How many blocks of width it shows: the body, arms swung out, held items and the cape. */
    private static final float VIEW_WIDTH_BLOCKS = 1.5F;
    /** Pixels of cursor offset per radian of turn, as in vanilla's inventory doll. */
    private static final float LOOK_DISTANCE = 40.0F;
    /** How far the head may turn from the body, in degrees, when the body has been dragged round. */
    private static final float MAX_HEAD_TURN = 60.0F;

    private final Minecraft minecraft;
    private Supplier<PlayerSkin> skin;
    private final AvatarRenderState state = new AvatarRenderState();
    private float rotationY = 30.0F;
    /** What the character wears and holds, before {@link ClientConfig} hides any of it. */
    private CharacterLoadout loadout = CharacterLoadout.EMPTY;
    /** The armor and item settings the doll was last dressed with, so it is redressed when they change. */
    private boolean dressedWithArmor;
    private boolean dressedWithItems;
    private final long startNanos = System.nanoTime();

    public CharacterDollWidget(int width, int height, Minecraft minecraft, Supplier<PlayerSkin> skin) {
        super(0, 0, width, height, CommonComponents.EMPTY);
        this.minecraft = minecraft;
        this.skin = skin;
        this.state.entityType = EntityTypes.PLAYER;
        this.state.boundingBoxWidth = 0.6F;
        this.state.boundingBoxHeight = PLAYER_HEIGHT;
        this.state.eyeHeight = 1.62F;
        this.dress();
    }

    /** Changes which skin the doll is drawn with; the supplier is read every frame. */
    public void setSkin(Supplier<PlayerSkin> skin) {
        this.skin = skin;
    }

    /** Dresses the doll in what the character wears and holds, as far as the settings show them. */
    public void setLoadout(CharacterLoadout loadout) {
        this.loadout = loadout;
        this.dress();
    }

    private void dress() {
        ClientConfig config = ClientConfig.get();
        this.dressedWithArmor = config.dollShowsArmor;
        this.dressedWithItems = config.dollShowsItems;
        CharacterLoadout loadout = getLoadout();
        AvatarRenderState state = this.state;
        state.headEquipment = renderableArmor(loadout.head(), EquipmentSlot.HEAD);
        state.chestEquipment = renderableArmor(loadout.chest(), EquipmentSlot.CHEST);
        state.legsEquipment = renderableArmor(loadout.legs(), EquipmentSlot.LEGS);
        state.feetEquipment = renderableArmor(loadout.feet(), EquipmentSlot.FEET);

        // Worn skulls and blocks that are not armor are drawn through the head item instead.
        state.headItem.clear();
        state.wornHeadType = null;
        state.wornHeadProfile = null;
        ItemStack head = loadout.head();
        if (head.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof AbstractSkullBlock skull) {
            state.wornHeadType = skull.getType();
            state.wornHeadProfile = head.get(DataComponents.PROFILE);
        } else if (!head.isEmpty() && state.headEquipment.isEmpty()) {
            this.minecraft.getItemModelResolver().updateForTopItem(state.headItem, head, ItemDisplayContext.HEAD, null, null, 0);
        }

        // The main hand follows the player's "Main Hand" option, as it does in game.
        HumanoidArm mainArm = this.minecraft.options.mainHand().get();
        state.mainArm = mainArm;
        this.setHand(HumanoidArm.RIGHT, mainArm == HumanoidArm.RIGHT ? loadout.mainHand() : loadout.offHand());
        this.setHand(HumanoidArm.LEFT, mainArm == HumanoidArm.LEFT ? loadout.mainHand() : loadout.offHand());
    }

    private @NonNull CharacterLoadout getLoadout() {
        CharacterLoadout full = this.loadout;
        // Anything worn on the head (skulls, pumpkins, ...) counts as armor here.
        CharacterLoadout loadout = new CharacterLoadout(
                this.dressedWithArmor ? full.head() : ItemStack.EMPTY,
                this.dressedWithArmor ? full.chest() : ItemStack.EMPTY,
                this.dressedWithArmor ? full.legs() : ItemStack.EMPTY,
                this.dressedWithArmor ? full.feet() : ItemStack.EMPTY,
                this.dressedWithItems ? full.mainHand() : ItemStack.EMPTY,
                this.dressedWithItems ? full.offHand() : ItemStack.EMPTY);
        return loadout;
    }

    private void setHand(HumanoidArm arm, ItemStack stack) {
        boolean right = arm == HumanoidArm.RIGHT;
        AvatarRenderState state = this.state;
        var renderState = right ? state.rightHandItemState : state.leftHandItemState;
        renderState.clear();
        if (!stack.isEmpty()) {
            this.minecraft.getItemModelResolver().updateForTopItem(
                    renderState, stack, right ? ItemDisplayContext.THIRD_PERSON_RIGHT_HAND : ItemDisplayContext.THIRD_PERSON_LEFT_HAND,
                    null, null, 0);
        }
        HumanoidModel.ArmPose pose = stack.isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
        if (right) {
            state.rightHandItemStack = stack;
            state.rightArmPose = pose;
        } else {
            state.leftHandItemStack = stack;
            state.leftArmPose = pose;
        }
    }

    private static ItemStack renderableArmor(ItemStack stack, EquipmentSlot slot) {
        return HumanoidArmorLayer.shouldRender(stack, slot) ? stack : ItemStack.EMPTY;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        AvatarRenderState state = this.state;
        ClientConfig config = ClientConfig.get();
        if (config.dollShowsArmor != this.dressedWithArmor || config.dollShowsItems != this.dressedWithItems) {
            this.dress();
        }
        // The skin may still be downloading when the widget is created, so read it every frame.
        state.skin = this.skin.get();
        state.showCape = this.minecraft.options.isModelPartEnabled(PlayerModelPart.CAPE);
        state.showHat = this.minecraft.options.isModelPartEnabled(PlayerModelPart.HAT);
        state.showJacket = this.minecraft.options.isModelPartEnabled(PlayerModelPart.JACKET);
        state.showLeftSleeve = this.minecraft.options.isModelPartEnabled(PlayerModelPart.LEFT_SLEEVE);
        state.showRightSleeve = this.minecraft.options.isModelPartEnabled(PlayerModelPart.RIGHT_SLEEVE);
        state.showLeftPants = this.minecraft.options.isModelPartEnabled(PlayerModelPart.LEFT_PANTS_LEG);
        state.showRightPants = this.minecraft.options.isModelPartEnabled(PlayerModelPart.RIGHT_PANTS_LEG);
        // The idle animation (arms gently swaying) is driven purely by the entity's age, so letting it
        // advance in real time, 20 ticks per second, is all that is needed to play it.
        state.ageInTicks = (System.nanoTime() - this.startNanos) / 50_000_000.0F;

        // Fit to whichever of the two dimensions is tighter, so the model is never clipped by the widget.
        float scale = Math.min(this.getHeight() / VIEW_HEIGHT_BLOCKS, this.getWidth() / VIEW_WIDTH_BLOCKS);

        // Look at the cursor the way vanilla's inventory doll does, but measured from the eyes rather than
        // the middle of the box, since this doll is much taller.
        float eyeX = (this.getX() + this.getRight()) / 2.0F;
        float eyeY = (this.getY() + this.getBottom()) / 2.0F - (state.eyeHeight - PLAYER_HEIGHT / 2.0F) * scale;
        boolean follow = ClientConfig.get().dollFollowsCursor;
        float yaw = follow ? (float) Math.atan((eyeX - mouseX) / LOOK_DISTANCE) : 0.0F;
        float pitch = follow ? (float) Math.atan((eyeY - mouseY) / LOOK_DISTANCE) : 0.0F;
        state.bodyRot = 180.0F + this.rotationY + yaw * 20.0F;
        // The head faces the cursor whichever way the body was dragged round, as far as a neck turns.
        // Without following, it simply faces the way the body does.
        state.yRot = !follow ? 0.0F
                : Mth.clamp(Mth.wrapDegrees(yaw * 40.0F - this.rotationY - yaw * 20.0F), -MAX_HEAD_TURN, MAX_HEAD_TURN);
        state.xRot = -pitch * 20.0F;

        Quaternionf tilt = new Quaternionf().rotateX(pitch * 20.0F * Mth.DEG_TO_RAD);
        Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI).mul(tilt);
        Vector3f translation = new Vector3f(0.0F, state.boundingBoxHeight / 2.0F + 0.0625F, 0.0F);
        graphics.entity(state, scale, translation, rotation, tilt, this.getX(), this.getY(), this.getRight(), this.getBottom());
    }

    @Override
    protected void onDrag(@NonNull MouseButtonEvent event, double dx, double dy) {
        this.rotationY -= (float) dx * ROTATION_SENSITIVITY;
    }

    @Override
    public void playDownSound(@NonNull SoundManager soundManager) {
    }

    @Override
    protected void updateWidgetNarration(@NonNull NarrationElementOutput output) {
    }

    @Override
    public @Nullable ComponentPath nextFocusPath(@NonNull FocusNavigationEvent navigationEvent) {
        return null;
    }
}
