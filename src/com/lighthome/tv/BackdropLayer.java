package com.lighthome.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * Full-bleed backdrop: current/next hero photo crossfade (time-based, 900ms), the
 * pre-rendered scrim bitmap drawn scaled over it, and the dim overlay that darkens
 * the backdrop once focus moves into the grid. Falls back to a calm, pre-built
 * gradient when there is no hero data yet (no text drawn here -- ContentLayer owns
 * text, and it is told to stay empty in that state).
 */
public final class BackdropLayer extends View implements MotionClock.Ticker {

    private static final long CROSSFADE_MS = Motion.HERO_CROSSFADE_MS;

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint leftScrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint bottomScrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint dimPaint = new Paint();
    private final Paint groundPaint = new Paint();
    private final Rect srcRect = new Rect();
    private final RectF dstRect = new RectF();
    private final RectF leftScrimDst = new RectF();
    private final RectF bottomScrimDst = new RectF();

    private Bitmap currentBitmap;
    private Bitmap previousBitmap; // only non-null during a crossfade, then recycled
    private long crossfadeStartNanos = 0L;
    private boolean crossfading = false;

    // Two tiny pre-rendered gradient bitmaps (256x1 and 1x256, ~1KB each) stretched to
    // screen size every frame instead of one ~2MB full-screen scrim bitmap. Also doubles
    // as the no-hero-data skeleton gradient (same draw path), so there is nothing extra
    // to build for that case.
    private Bitmap leftScrimBitmap;
    private Bitmap bottomScrimBitmap;

    private float dimAlpha = 0f; // 0..0.55, follows grid progress
    private float scale = 1f;

    // Scroll-linked scrim: the bottom scrim rises with the content as the page spring
    // moves from hero (0) to grid (GRID_SCROLL_SHIFT design px), then stays pinned there
    // even if individual rows scroll further -- see HomeView.tick().
    private float pageShiftDesignPx = 0f;

    public BackdropLayer(Context context) {
        super(context);
        setWillNotDraw(false);
        leftScrimBitmap = GlassRenderer.buildLeftScrimBitmap(256);
        bottomScrimBitmap = GlassRenderer.buildBottomScrimBitmap(256);
        groundPaint.setColor(Metrics.COLOR_GROUND);
    }

    public void setScale(float s) {
        scale = s;
    }

    /**
     * Design-px amount the page content has shifted up from its hero rest position,
     * already clamped to [0, GRID_SCROLL_SHIFT] by the caller (HomeView) so the scrim
     * stops rising once rows scroll past the first page shift.
     */
    public void setPageShift(float shiftDesignPx) {
        if (shiftDesignPx != pageShiftDesignPx) {
            pageShiftDesignPx = shiftDesignPx;
            invalidate();
        }
    }

    /** Immediately shows a bitmap with no crossfade (first load / resume). */
    public void setCurrentImmediate(Bitmap bmp) {
        recyclePrevious();
        if (currentBitmap != null && currentBitmap != bmp) currentBitmap.recycle();
        currentBitmap = bmp;
        crossfading = false;
        invalidate();
    }

    /** Starts a 900ms crossfade from whatever is current to the given bitmap. */
    public void crossfadeTo(Bitmap bmp) {
        recyclePrevious();
        previousBitmap = currentBitmap;
        currentBitmap = bmp;
        crossfadeStartNanos = System.nanoTime();
        crossfading = true;
        invalidate();
    }

    public boolean isCrossfading() {
        return crossfading;
    }

    /** Call once per frame while crossfading; returns true if still fading. */
    @Override
    public boolean tick(float dt) {
        if (!crossfading) return false;
        long elapsedMs = (System.nanoTime() - crossfadeStartNanos) / 1_000_000L;
        if (elapsedMs >= CROSSFADE_MS) {
            crossfading = false;
            recyclePrevious();
            invalidate();
            return false;
        }
        invalidate();
        return true;
    }

    private void recyclePrevious() {
        if (previousBitmap != null) {
            previousBitmap.recycle();
            previousBitmap = null;
        }
    }

    public void setDimAlpha(float a) {
        if (a != dimAlpha) {
            dimAlpha = a;
            invalidate();
        }
    }

    public void clearHeroBitmaps() {
        if (currentBitmap != null) { currentBitmap.recycle(); currentBitmap = null; }
        recyclePrevious();
        crossfading = false;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        if (currentBitmap == null) {
            drawSkeleton(canvas, w, h);
        } else if (crossfading && previousBitmap != null) {
            long elapsedMs = (System.nanoTime() - crossfadeStartNanos) / 1_000_000L;
            float t = Math.min(1f, elapsedMs / (float) CROSSFADE_MS);
            float eased = smoothstep(t);
            drawCover(canvas, previousBitmap, w, h, 255);
            bitmapPaint.setAlpha(Math.round(eased * 255));
            drawCoverWithPaint(canvas, currentBitmap, w, h);
            bitmapPaint.setAlpha(255);
        } else {
            drawCover(canvas, currentBitmap, w, h, 255);
        }

        drawScrim(canvas, w, h);

        if (dimAlpha > 0.001f) {
            dimPaint.setColor(0xFF000000);
            dimPaint.setAlpha(Math.round(dimAlpha * 255));
            canvas.drawRect(0, 0, w, h, dimPaint);
        }
    }

    private void drawSkeleton(Canvas canvas, int w, int h) {
        canvas.drawColor(Metrics.COLOR_GROUND);
        drawScrim(canvas, w, h);
    }

    /**
     * Draws two independent scrims instead of one combined, alpha-faded bitmap:
     *
     *  - LEFT: a full-bleed horizontal vignette, fixed in place (not translated by the
     *    page shift). Its own paint alpha fades 255->180 as the page settles into the
     *    grid -- there's no more hero text to separate from at that point.
     *  - BOTTOM: a full-bleed vertical gradient translated up by the page shift exactly
     *    like before, but drawn at a FIXED alpha of 255 and with its bottom row forced
     *    bit-identical to groundPaint's colour (see GlassRenderer). The ground rect below
     *    it is drawn overlapping it by 2px so no sub-pixel rounding gap can show either.
     *
     * The bug this replaces: one bitmap held both gradients, and fading its paint alpha
     * to fade the left vignette ALSO dropped the bottom band's nominally-opaque pixels to
     * ~70% alpha right next to the fully-opaque ground rect under it -- a hard seam. Never
     * fading the bottom scrim's alpha removes that coupling entirely.
     * Exactly two drawBitmap calls + one drawRect; no new bitmaps, no per-frame allocation.
     */
    private void drawScrim(Canvas canvas, int w, int h) {
        float p = Metrics.GRID_SCROLL_SHIFT > 0 ? pageShiftDesignPx / Metrics.GRID_SCROLL_SHIFT : 0f;
        float shiftPx = pageShiftDesignPx * scale;

        leftScrimDst.set(0, 0, w, h);
        leftScrimPaint.setAlpha(Math.round(lerp(255f, 180f, p)));
        canvas.drawBitmap(leftScrimBitmap, null, leftScrimDst, leftScrimPaint);

        bottomScrimDst.set(0, -shiftPx, w, h - shiftPx);
        canvas.drawBitmap(bottomScrimBitmap, null, bottomScrimDst, bottomScrimPaint);

        if (shiftPx > 0.5f) {
            // Overlap the scrim's bottom edge by 2px (both are the same opaque colour
            // there) so a sub-pixel bitmap-scaling rounding gap can never show through.
            canvas.drawRect(0, h - shiftPx - 2f, w, h, groundPaint);
        }
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private void drawCover(Canvas canvas, Bitmap bmp, int w, int h, int alpha) {
        bitmapPaint.setAlpha(alpha);
        drawCoverWithPaint(canvas, bmp, w, h);
    }

    private void drawCoverWithPaint(Canvas canvas, Bitmap bmp, int w, int h) {
        if (bmp == null || bmp.isRecycled()) return;
        int bw = bmp.getWidth();
        int bh = bmp.getHeight();
        float srcAspect = bw / (float) bh;
        float dstAspect = w / (float) h;
        int sw, sh, sx, sy;
        if (srcAspect > dstAspect) {
            sh = bh;
            sw = Math.round(bh * dstAspect);
            sx = (bw - sw) / 2;
            sy = 0;
        } else {
            sw = bw;
            sh = Math.round(bw / dstAspect);
            sx = 0;
            sy = (bh - sh) / 2;
        }
        srcRect.set(sx, sy, sx + sw, sy + sh);
        dstRect.set(0, 0, w, h);
        canvas.drawBitmap(bmp, srcRect, dstRect, bitmapPaint);
    }

    private static float smoothstep(float t) {
        return t * t * (3f - 2f * t);
    }
}
