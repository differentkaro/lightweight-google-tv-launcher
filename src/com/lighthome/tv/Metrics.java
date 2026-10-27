package com.lighthome.tv;

/**
 * All layout numbers from the design boards, expressed in 1920x1080 design pixels.
 * Every layer converts design px to screen px by multiplying by {@link #scale}, which
 * is recomputed once per size change (viewWidth / 1920f). Keeping this arithmetic in one
 * place means layers never disagree about where something is.
 */
public final class Metrics {

    private Metrics() {}

    public static final int CANVAS_W = 1920;
    public static final int CANVAS_H = 1080;

    public static final int MARGIN_X = 96;
    public static final int TOPBAR_TOP = 54;

    // Top bar
    public static final int CLOCK_SIZE = 34;
    public static final int DATE_SIZE = 22; // bumped from the design's 20 for TV legibility, per review
    public static final int DATE_MARGIN_TOP = 6;
    public static final int PILL_FONT = 22; // floor: all text >= 22px (except small-caps eyebrows, also 22)
    public static final int PILL_PAD_V = 14;
    public static final int PILL_PAD_H = 28;
    public static final int CIRCLE_SIZE = 56;
    public static final int TOPBAR_GAP = 16;

    // Hero text block
    public static final int HERO_TEXT_LEFT = MARGIN_X;
    public static final int HERO_TEXT_TOP = 430; // v7: raised from 454 to make room for the wider gaps below while keeping CTA bottom <= ~800
    // v7 design-review: vertical rhythm fix -- each gap in the eyebrow/title/meta/overview/CTA
    // block now has its own minimum, per the board (eyebrow->title >=24, title->meta >=16,
    // meta->overview >=12, overview->CTA >=32), budgeted from the CTA-bottom cap upward.
    public static final int HERO_GAP_EYEBROW_TITLE = 24;
    public static final int HERO_GAP_TITLE_META = 16;
    public static final int HERO_GAP_META_OVERVIEW = 12;
    public static final int HERO_GAP_OVERVIEW_CTA = 32;
    public static final int HERO_PROVIDER_SIZE = 22;
    public static final float HERO_PROVIDER_LETTER_SPACING = 0.08f;
    public static final int HERO_EYEBROW_CHIP_GAP = 16; // >=16px gap between provider label and the priority chip
    // Chip (priority label): v7 design-review -- much smaller/subtler than before.
    public static final int HERO_CHIP_FONT = 18;
    public static final int HERO_CHIP_H = 30;
    public static final int HERO_CHIP_PAD_H = 12;
    public static final int HERO_CHIP_FILL_COLOR = 0x1FFFFFFF; // rgba(255,255,255,0.12)
    public static final int HERO_TITLE_SIZE = 76;
    public static final int HERO_TITLE_SIZE_WRAPPED = 58; // used when the title needs 2 lines, to keep CTA bottom <= ~800
    public static final int HERO_TITLE_MAX_W = 1100;
    public static final int HERO_TITLE_MAX_LINES = 2; // 1 line preferred, wraps to 2 then ellipsizes
    public static final int HERO_META_SIZE = 24;
    public static final int HERO_OVERVIEW_SIZE = 26;
    public static final int HERO_OVERVIEW_LINE_H = 36;
    public static final int HERO_OVERVIEW_MAX_W = 880;
    public static final int HERO_CTA_MARGIN_TOP = 12;
    public static final int HERO_CTA_FONT = 26;
    public static final int HERO_CTA_PAD_V = 20;
    public static final int HERO_CTA_PAD_H = 40;
    public static final float HERO_CTA_FOCUS_SCALE = 1.06f;
    public static final int HERO_CTA_BOTTOM_MAX = 800; // design-review: CTA bottom must stay <= ~800

    // Page indicator (vertically aligned with the CTA row's center; right-anchored)
    public static final int DOTS_RIGHT = MARGIN_X;
    public static final int DOT_SIZE = 8;
    public static final int DOT_ACTIVE_W = 28;
    public static final int DOT_GAP = 10;

    // "Your apps" label + grid -- design-review vertical rhythm fix
    public static final int LABEL_LEFT = MARGIN_X;
    public static final int LABEL_TOP_HERO = 860;
    public static final int LABEL_TOP_GRID = 260;
    public static final int LABEL_SIZE = 28;
    public static final int GRID_TOP_HERO = 932; // peek row intentionally bleeds off the bottom
    public static final int GRID_TOP_GRID = 332;
    public static final int GRID_SCROLL_SHIFT = 600; // design-review: fixed page-shift amount

    public static final int TILE_W = 312;
    public static final int TILE_H = 176;
    public static final int TILE_RADIUS = 22;
    public static final int COLS = 5;
    public static final int COL_GAP = 42;
    public static final int ROW_GAP = 64;
    public static final int ROW_PITCH = TILE_H + ROW_GAP; // 240
    public static final int TILE_LABEL_MARGIN_TOP = 14; // ring-bottom (tile-bottom + 8) + 6, on the SCALED tile
    public static final int TILE_LABEL_SIZE = 22;
    public static final int TILE_LABEL_MAX_W = 280;
    public static final int TILE_EDGE_STROKE_COLOR = 0x24FFFFFF; // 1px rgba(255,255,255,0.14) on every tile

    public static final int FOCUS_RING_OFFSET = 5;
    public static final int FOCUS_RING_STROKE = 3;
    public static final int FOCUS_RING_RADIUS = 27;
    public static final int FOCUS_KEYLINE_WIDTH = 2; // dark keyline between tile edge and the white ring
    public static final int FOCUS_KEYLINE_COLOR = 0xFF0A0A0D;

    // Featured peeking hint, anchored from its BOTTOM so it sits >=24px above "Your apps"
    public static final int HINT_BOTTOM = LABEL_TOP_GRID - 24; // 236
    public static final int HINT_CHEVRON_SIZE = 22;
    public static final int HINT_LABEL_SIZE = 22;
    public static final int HINT_GAP = 4;

    // Inputs drawer -- design-review: widened, asymmetric padding
    public static final int DRAWER_W = 600;
    public static final int DRAWER_PAD_LEFT = 48;
    public static final int DRAWER_PAD_RIGHT = 96;
    public static final int DRAWER_PAD_TOP = 64;
    public static final int DRAWER_RADIUS = 28;
    public static final int DRAWER_TITLE_SIZE = 34;
    public static final int DRAWER_ROW_H = 80;
    public static final int DRAWER_ROW_GAP = 8;
    public static final int DRAWER_ROW_RADIUS = 16;
    public static final int DRAWER_ICON_SIZE = 44;
    public static final int DRAWER_ICON_RADIUS = 12;
    public static final int DRAWER_ROW_LABEL_SIZE = 22;
    public static final int DRAWER_STATUS_SIZE = 22;
    public static final int DRAWER_HELPER_SIZE = 22;

    // Glass sheets (settings / hold-OK menu) -- design-review: opaque-ish fill, no footnote
    public static final int SHEET_W = 420;
    public static final int SHEET_RADIUS = 28;
    public static final int SHEET_PAD = 32;
    public static final int SHEET_ROW_H = 68;
    public static final int SHEET_ROW_GAP = 8;
    public static final int SHEET_ROW_RADIUS = 16;
    public static final int SHEET_GAP_FROM_TILE = 32; // never overlaps the focused tile's ring
    public static final int SHEET_FILL_COLOR = 0xF0101014; // rgba(16,16,20,0.94)

    // Move mode -- design-review: chevrons relative to the UNSCALED tile, no caption
    public static final float MOVE_SCALE = 1.12f;
    public static final int MOVE_CHEVRON_V_OFFSET = 44;
    public static final int MOVE_CHEVRON_H_OFFSET = 52;
    public static final int HINT_BAR_BOTTOM = 54;
    public static final int HINT_BAR_PAD_V = 18;
    public static final int HINT_BAR_PAD_H = 36;
    public static final int HINT_BAR_FONT = 22;

    // Toast (hide/undo)
    public static final int TOAST_BOTTOM = 72;
    public static final int TOAST_PAD_V = 18;
    public static final int TOAST_PAD_H = 32;
    public static final int TOAST_FONT = 22;
    public static final long TOAST_DURATION_MS = 5000L;

    // Colors
    public static final int COLOR_GROUND = 0xFF0A0A0D;
    public static final int COLOR_TEXT = 0xFFFFFFFF;
    public static final int COLOR_TEXT_70 = 0xB3FFFFFF; // floor for any text drawn over a photo/backdrop
    public static final int COLOR_TEXT_50 = 0x80FFFFFF; // only ever over fully-opaque dark surfaces
    public static final int COLOR_ACCENT_MOVE = 0xFFFFC24B;
    public static final int COLOR_DESTRUCTIVE = 0xFFFF6B5E; // Uninstall ONLY
    public static final int COLOR_GLASS_FILL = 0x1AFFFFFF;   // rgba(255,255,255,0.10)
    public static final int COLOR_GLASS_STROKE = 0x24FFFFFF; // rgba(255,255,255,0.14)

    public static float scaleFor(int viewWidthPx) {
        if (viewWidthPx <= 0) return 1f;
        return viewWidthPx / (float) CANVAS_W;
    }

    /** Canonical (GRID-zone) slot position for a linear grid index; see ContentLayer's scroll math. */
    public static float tileLeft(int col) {
        return MARGIN_X + col * (TILE_W + COL_GAP);
    }

    public static float tileTop(int row) {
        return GRID_TOP_GRID + row * ROW_PITCH;
    }
}
