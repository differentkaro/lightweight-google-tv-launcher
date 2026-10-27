package com.lighthome.tv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;

/**
 * Builds the handful of bitmaps that stand in for "live" effects the spec forbids
 * (live blur, per-frame shadow layers): a pre-blurred downscaled copy of the backdrop
 * for glass-pill fills (recomputed once per hero slide, off the UI thread).
 */
public final class GlassRenderer {

    private GlassRenderer() {}

    /** Downscale + box blur a source bitmap into a small glass-shader source. Call off UI thread. */
    public static Bitmap buildGlassSource(Bitmap source, int outW, int outH) {
        if (source == null) return null;
        Bitmap small = Bitmap.createScaledBitmap(source, outW, outH, true);
        Bitmap blurred = BlurUtil.boxBlur(small, 4, 2);
        if (blurred != small) small.recycle();
        return blurred;
    }

    /**
     * 256x1 horizontal gradient: the left-edge vignette, stretched full-screen width every
     * frame. Its own alpha is faded 255->180 by the caller as the page settles into the
     * grid (no more hero text to separate from at that point). Kept separate from the
     * bottom scrim so that fade never touches the bottom band's opacity -- that coupling
     * was the seam bug (see buildBottomScrimBitmap).
     */
    public static Bitmap buildLeftScrimBitmap(int w) {
        Bitmap bmp = Bitmap.createBitmap(w, 1, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        paint.setShader(new LinearGradient(0, 0, w * 0.62f, 0,
                new int[]{0xE00A0A0D, 0x8C0A0A0D, 0x000A0A0D},
                new float[]{0f, 0.52f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, 1, paint);
        return bmp;
    }

    /**
     * 1x256 vertical gradient: the bottom scrim, stretched full-screen width and (scaled)
     * height, translated up with the page shift -- but ALWAYS drawn at full paint alpha.
     * Its last row is forced to Metrics.COLOR_GROUND at full opacity, exactly matching the
     * solid ground rect drawn below it, so the two can overlap by a couple of px with no
     * visible seam and no dependence on the left-scrim's fade.
     *
     * Previously both scrims lived in one bitmap whose paint alpha was faded 255->180 as
     * the page scrolled (to fade the left vignette); that also dropped the bottom band's
     * "solid" pixels to ~70% opacity right next to the fully-opaque ground rect below it,
     * which was the hard horizontal seam. Splitting them out and pinning this one's alpha
     * at 255 fixes that at the source.
     */
    public static Bitmap buildBottomScrimBitmap(int h) {
        Bitmap bmp = Bitmap.createBitmap(1, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        paint.setShader(new LinearGradient(0, h * 0.40f, 0, h,
                new int[]{0x000A0A0D, 0xBF0A0A0D, 0xFF0A0A0D},
                new float[]{0f, 0.65f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, 1, h, paint);
        // Belt-and-braces: guarantee the very last row is bit-for-bit the same opaque
        // colour as groundPaint, regardless of how the gradient/dither rounded it.
        bmp.setPixel(0, h - 1, Metrics.COLOR_GROUND);
        return bmp;
    }
}
