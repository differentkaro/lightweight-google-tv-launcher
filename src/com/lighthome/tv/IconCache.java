package com.lighthome.tv;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import java.util.HashMap;
import java.util.Map;

/**
 * Rasterises every Material Symbols VectorDrawable (res/drawable/ic_*.xml) ONCE at app
 * startup -- at the exact design-px size it is actually drawn at -- into a tiny ALPHA_8
 * mask bitmap, then throws the inflated VectorDrawable (and its parsed Path/PathParser
 * object graph plus its own internal ARGB_8888 software-rasterisation cache) away.
 * onDraw call sites never inflate or re-rasterise a vector again -- they look an
 * already-built mask bitmap up by name and draw it through a cached tint Paint, which
 * allocates nothing per frame. An ALPHA_8 mask is 1 byte/px vs. the 4 bytes/px a
 * VectorDrawable's own cached bitmap would use, and nothing here retains Resources /
 * Path data for the lifetime of the process the way the live Drawable objects did.
 *
 * Rendering is otherwise pixel-identical to the old live-Drawable path: both rasterise
 * at the *design*-px bounds and let the ambient canvas.scale(...) stretch that small
 * bitmap up to the device resolution with bilinear filtering, so this is a pure memory
 * optimisation, not a visual change.
 */
public final class IconCache {

    private static final Map<String, Bitmap> ICONS = new HashMap<>();
    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final RectF DST = new RectF();

    private IconCache() {}

    public static final String SETTINGS = "ic_settings";
    public static final String TUNE = "ic_tune";
    public static final String INPUT = "ic_input";
    public static final String LOGIN = "ic_login";
    public static final String HDMI = "ic_settings_input_hdmi";
    public static final String COMPOSITE = "ic_settings_input_composite";
    public static final String LIVE_TV = "ic_live_tv";
    public static final String PLAY_ARROW = "ic_play_arrow";
    public static final String CHEVRON_UP = "ic_keyboard_arrow_up";
    public static final String VISIBILITY_OFF = "ic_visibility_off";
    public static final String REFRESH = "ic_refresh";
    public static final String OPEN_WITH = "ic_open_with";
    public static final String INFO = "ic_info";
    public static final String DELETE = "ic_delete";

    private static final String[] ALL = {
            SETTINGS, TUNE, INPUT, LOGIN, HDMI, COMPOSITE, LIVE_TV, PLAY_ARROW,
            CHEVRON_UP, VISIBILITY_OFF, REFRESH, OPEN_WITH, INFO, DELETE
    };

    // Design-px size each icon is actually drawn at (see ContentLayer/OverlayLayer call
    // sites). Rasterising at this exact size keeps the result pixel-identical to the old
    // VectorDrawable rendering -- anything not listed here is only ever drawn at the
    // default row/pill icon size (24).
    private static final Map<String, Integer> RENDER_SIZE = new HashMap<>();
    static {
        RENDER_SIZE.put(SETTINGS, 34);
        RENDER_SIZE.put(TUNE, 34);
        RENDER_SIZE.put(LOGIN, 28);
        RENDER_SIZE.put(INFO, 28);
        RENDER_SIZE.put(PLAY_ARROW, 32);
        RENDER_SIZE.put(CHEVRON_UP, (int) Math.ceil(Metrics.HINT_CHEVRON_SIZE));
    }
    private static final int DEFAULT_SIZE = 24;

    /** Call once from the launcher Activity's onCreate(). Rasterises every vector exactly once. */
    public static void preload(Context context) {
        if (!ICONS.isEmpty()) return;
        Resources res = context.getResources();
        String pkg = context.getPackageName();
        for (String name : ALL) {
            int id = res.getIdentifier(name, "drawable", pkg);
            if (id == 0) continue;
            Drawable d = res.getDrawable(id, context.getTheme());
            if (d == null) continue;
            Integer size = RENDER_SIZE.get(name);
            int sizePx = size != null ? size : DEFAULT_SIZE;
            ICONS.put(name, rasterize(d, sizePx));
        }
    }

    /** Draws `d` into a fresh ALPHA_8 mask bitmap at sizePx square. Not retained after this call. */
    private static Bitmap rasterize(Drawable d, int sizePx) {
        sizePx = Math.max(1, sizePx);
        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ALPHA_8);
        Canvas canvas = new Canvas(bmp);
        d.setBounds(0, 0, sizePx, sizePx);
        d.draw(canvas);
        return bmp;
    }

    /** Draws icon `name` tinted `color`, centered at (cx, cy), `sizePx` square. No allocation. */
    public static void draw(Canvas canvas, String name, float cx, float cy, float sizePx, int color) {
        Bitmap bmp = ICONS.get(name);
        if (bmp == null) return;
        float half = sizePx / 2f;
        DST.set(cx - half, cy - half, cx - half + sizePx, cy - half + sizePx);
        // ALPHA_8 source: the paint's colour supplies the RGB+A, the bitmap supplies
        // per-pixel coverage -- the same SRC_IN-over-a-flat-tint result the old
        // PorterDuffColorFilter(SRC_IN) approach produced, with no filter object needed.
        PAINT.setColor(color);
        canvas.drawBitmap(bmp, null, DST, PAINT);
    }

    public static boolean has(String name) {
        return ICONS.containsKey(name);
    }
}
