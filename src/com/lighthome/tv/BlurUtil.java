package com.lighthome.tv;

import android.graphics.Bitmap;

/**
 * Cheap in-process box blur. Intended to run on a tiny (e.g. 96x54) bitmap so the
 * O(w*h*radius) cost stays negligible, then the caller stretches the result to fill
 * the frosted panel. No RenderEffect / live blur is used.
 */
public final class BlurUtil {

    private BlurUtil() {}

    public static Bitmap boxBlur(Bitmap src, int radius, int passes) {
        if (src == null) return null;
        Bitmap bmp = src.copy(Bitmap.Config.ARGB_8888, true);
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);
        for (int p = 0; p < passes; p++) {
            horizontalBlur(pixels, w, h, radius);
            verticalBlur(pixels, w, h, radius);
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h);
        return bmp;
    }

    private static void horizontalBlur(int[] pixels, int w, int h, int radius) {
        int[] tmp = new int[w];
        for (int y = 0; y < h; y++) {
            int rowStart = y * w;
            long sumA = 0, sumR = 0, sumG = 0, sumB = 0;
            int count = 0;
            for (int x = -radius; x <= radius; x++) {
                int xi = clamp(x, 0, w - 1);
                int px = pixels[rowStart + xi];
                sumA += (px >>> 24) & 0xFF;
                sumR += (px >>> 16) & 0xFF;
                sumG += (px >>> 8) & 0xFF;
                sumB += px & 0xFF;
                count++;
            }
            for (int x = 0; x < w; x++) {
                tmp[x] = packAvg(sumA, sumR, sumG, sumB, count);
                int addX = clamp(x + radius + 1, 0, w - 1);
                int subX = clamp(x - radius, 0, w - 1);
                int addPx = pixels[rowStart + addX];
                int subPx = pixels[rowStart + subX];
                sumA += ((addPx >>> 24) & 0xFF) - ((subPx >>> 24) & 0xFF);
                sumR += ((addPx >>> 16) & 0xFF) - ((subPx >>> 16) & 0xFF);
                sumG += ((addPx >>> 8) & 0xFF) - ((subPx >>> 8) & 0xFF);
                sumB += (addPx & 0xFF) - (subPx & 0xFF);
            }
            System.arraycopy(tmp, 0, pixels, rowStart, w);
        }
    }

    private static void verticalBlur(int[] pixels, int w, int h, int radius) {
        int[] tmp = new int[h];
        for (int x = 0; x < w; x++) {
            long sumA = 0, sumR = 0, sumG = 0, sumB = 0;
            int count = 0;
            for (int y = -radius; y <= radius; y++) {
                int yi = clamp(y, 0, h - 1);
                int px = pixels[yi * w + x];
                sumA += (px >>> 24) & 0xFF;
                sumR += (px >>> 16) & 0xFF;
                sumG += (px >>> 8) & 0xFF;
                sumB += px & 0xFF;
                count++;
            }
            for (int y = 0; y < h; y++) {
                tmp[y] = packAvg(sumA, sumR, sumG, sumB, count);
                int addY = clamp(y + radius + 1, 0, h - 1);
                int subY = clamp(y - radius, 0, h - 1);
                int addPx = pixels[addY * w + x];
                int subPx = pixels[subY * w + x];
                sumA += ((addPx >>> 24) & 0xFF) - ((subPx >>> 24) & 0xFF);
                sumR += ((addPx >>> 16) & 0xFF) - ((subPx >>> 16) & 0xFF);
                sumG += ((addPx >>> 8) & 0xFF) - ((subPx >>> 8) & 0xFF);
                sumB += (addPx & 0xFF) - (subPx & 0xFF);
            }
            for (int y = 0; y < h; y++) {
                pixels[y * w + x] = tmp[y];
            }
        }
    }

    private static int packAvg(long a, long r, long g, long b, int count) {
        return ((int) (a / count) << 24) | ((int) (r / count) << 16) | ((int) (g / count) << 8) | (int) (b / count);
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }
}
