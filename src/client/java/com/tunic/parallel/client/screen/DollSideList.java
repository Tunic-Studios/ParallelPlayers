package com.tunic.parallel.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ObjectSelectionList;

/**
 * A selection list that spans the whole screen width so its dark background also sits behind the paper
 * doll, while its rows are placed to the right of the doll (see {@link DollPanelLayout}).
 */
abstract class DollSideList<E extends ObjectSelectionList.Entry<E>> extends ObjectSelectionList<E> {

    /** Left edge of the rows; everything to the left of it belongs to the paper doll. */
    int rowsLeft;

    DollSideList(Minecraft minecraft, int width, int height, int y, int itemHeight) {
        super(minecraft, width, height, y, itemHeight);
    }

    @Override
    public int getRowLeft() {
        return this.rowsLeft;
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        // The doll panel is only part of the list's background; clicks and drags there belong to the doll.
        return mouseX >= this.rowsLeft - DollPanelLayout.DOLL_PADDING && super.isMouseOver(mouseX, mouseY);
    }
}
