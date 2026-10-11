package com.tunic.parallel_players.client.screen;

import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;

/**
 * Where the paper doll and the list rows go on screens that show both: the rows are centred at
 * {@link #LIST_CENTER} of the screen width and the doll is centred in the space to their left.
 *
 * @param rowsLeft   left edge of the list rows
 * @param dollX      left edge of the doll's box
 * @param dollY      top edge of the doll's box
 * @param dollWidth  width of the doll's box
 * @param dollHeight height of the doll's box
 */
record DollPanelLayout(int rowsLeft, int dollX, int dollY, int dollWidth, int dollHeight) {

    static final int DOLL_MIN_WIDTH = 40;
    static final int DOLL_MIN_HEIGHT = 40;
    static final int DOLL_MAX_WIDTH = 160;
    static final int DOLL_PADDING = 8;
    static final int DOLL_LABEL_HEIGHT = 14;

    /**
     * Where the list is centred, as a fraction of the screen width: 0.5 is the middle of the screen,
     * 1.0 the right edge.
     */
    static final float LIST_CENTER = 0.7F;
    /** Least space kept between the list and the right edge of the screen, for the scrollbar. */
    static final int LIST_RIGHT_MARGIN = 24;

    /**
     * Positions a list and the doll on a screen that shows both, below the header and above the footer.
     */
    static void place(int screenWidth, HeaderAndFooterLayout layout, DollSideList<?> list, CharacterDollWidget doll) {
        int top = layout.getHeaderHeight();
        int height = layout.getContentHeight();
        DollPanelLayout panel = of(screenWidth, top, height, list.getRowWidth());
        list.rowsLeft = panel.rowsLeft();
        list.updateSizeAndPosition(screenWidth, height, 0, top);
        doll.setSize(panel.dollWidth(), panel.dollHeight());
        doll.setPosition(panel.dollX(), panel.dollY());
    }

    static DollPanelLayout of(int screenWidth, int top, int contentHeight, int rowWidth) {
        // Never closer to the right edge than the scrollbar needs, nor so far left that the doll would not fit.
        int rowsLeft = Math.round(screenWidth * LIST_CENTER - rowWidth / 2.0F);
        rowsLeft = Math.min(rowsLeft, screenWidth - rowWidth - LIST_RIGHT_MARGIN);
        rowsLeft = Math.max(rowsLeft, DOLL_MIN_WIDTH + 2 * DOLL_PADDING);

        int dollHeight = Math.max(DOLL_MIN_HEIGHT, contentHeight - 2 * DOLL_PADDING - DOLL_LABEL_HEIGHT);
        int maxDollWidth = Math.max(DOLL_MAX_WIDTH, dollHeight * 3 / 4);
        int dollWidth = Math.clamp(rowsLeft - 2 * DOLL_PADDING, DOLL_MIN_WIDTH, maxDollWidth);

        return new DollPanelLayout(rowsLeft, Math.max(0, (rowsLeft - dollWidth) / 2), top + DOLL_PADDING, dollWidth, dollHeight);
    }
}
