package com.lighthome.tv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

public final class ImageUtils {

    private ImageUtils() {}

    /** Decode a file scaled down to at most maxW x maxH, in RGB_565. Call off the main thread. */
    public static Bitmap decodeSampled(String path, int maxW, int maxH) {
        if (path == null) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int sample = 1;
        int halfW = bounds.outWidth / 2;
        int halfH = bounds.outHeight / 2;
        while ((halfW / sample) >= maxW && (halfH / sample) >= maxH) {
            sample *= 2;
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(path, opts);
    }

    public static Bitmap tinyThumbnail(Bitmap src, int w, int h) {
        if (src == null) return null;
        return Bitmap.createScaledBitmap(src, w, h, true);
    }

    /**
     * Renders one app's tile ONCE into a small RGB_565 bitmap: the banner flattened
     * (center-cropped) onto the card color, or an icon + label on the card if there is
     * no banner. Called on the background executor; the result is what gets cached to
     * disk and held in the LruCache -- the Drawables themselves are never retained.
     */
    public static Bitmap renderTileBitmap(Drawable banner, Drawable icon, String label, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(0xFF1C1C22);

        if (banner != null) {
            drawDrawableCenterCrop(canvas, banner, w, h);
            return out;
        }

        if (icon != null) {
            int iconSize = Math.round(h * 0.42f);
            int iconLeft = (w - iconSize) / 2;
            int iconTop = Math.round(h * 0.14f);
            icon.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize);
            icon.draw(canvas);
        }

        if (label != null && label.length() > 0) {
            TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(0xFFFFFFFF);
            paint.setTextSize(h * 0.12f);
            paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            int textWidth = Math.round(w * 0.86f);
            StaticLayout layout = StaticLayout.Builder.obtain(label, 0, label.length(), paint, textWidth)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setMaxLines(1)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setIncludePad(false)
                    .build();
            canvas.save();
            canvas.translate((w - textWidth) / 2f, h * 0.70f);
            layout.draw(canvas);
            canvas.restore();
        }
        return out;
    }

    private static void drawDrawableCenterCrop(Canvas canvas, Drawable d, int w, int h) {
        int iw = d.getIntrinsicWidth();
        int ih = d.getIntrinsicHeight();
        if (iw <= 0 || ih <= 0) {
            d.setBounds(0, 0, w, h);
            d.draw(canvas);
            return;
        }
        float scale = Math.max(w / (float) iw, h / (float) ih);
        int sw = Math.round(iw * scale);
        int sh = Math.round(ih * scale);
        int left = (w - sw) / 2;
        int top = (h - sh) / 2;
        d.setBounds(left, top, left + sw, top + sh);
        d.draw(canvas);
    }

    /**
     * Draws a bitmap into destRect with rounded corners via BitmapShader + drawRoundRect
     * (never clipPath, which forces an extra off-screen pass). Caller owns paint/matrix
     * so this allocates nothing; only the shader object is created, and only when the
     * bitmap reference changed since the caller should cache that too where possible.
     */
    public static void drawBitmapRounded(Canvas canvas, Bitmap bmp, RectF destRect, float radius,
                                          Paint scratchPaint, Matrix scratchMatrix) {
        if (bmp == null || bmp.isRecycled()) return;
        BitmapShader shader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        drawShaderRounded(canvas, shader, bmp.getWidth(), bmp.getHeight(), destRect, radius, scratchPaint, scratchMatrix);
    }

    /**
     * Same as {@link #drawBitmapRounded} but takes an already-built shader (e.g. AppInfo.tileShader,
     * built once when the bitmap was rendered) so onDraw never allocates a new BitmapShader.
     */
    public static void drawShaderRounded(Canvas canvas, Shader shader, int bmpW, int bmpH, RectF destRect,
                                          float radius, Paint scratchPaint, Matrix scratchMatrix) {
        if (shader == null) return;
        float sx = destRect.width() / bmpW;
        float sy = destRect.height() / bmpH;
        scratchMatrix.reset();
        scratchMatrix.setScale(sx, sy);
        scratchMatrix.postTranslate(destRect.left, destRect.top);
        shader.setLocalMatrix(scratchMatrix);
        scratchPaint.setShader(shader);
        canvas.drawRoundRect(destRect, radius, radius, scratchPaint);
        scratchPaint.setShader(null);
    }
}
