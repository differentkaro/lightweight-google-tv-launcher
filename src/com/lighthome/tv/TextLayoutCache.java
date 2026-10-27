package com.lighthome.tv;

import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Caches StaticLayouts keyed by (text, paint signature, width, maxLines) so onDraw never
 * builds one. Cardinality here is tiny (a handful of hero slides, a handful of tile
 * labels, static chrome strings), so a simple bounded LinkedHashMap LRU is plenty.
 */
public final class TextLayoutCache {

    private static final int MAX_ENTRIES = 48;

    private final Map<String, StaticLayout> cache = new LinkedHashMap<String, StaticLayout>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, StaticLayout> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    public StaticLayout get(String text, TextPaint paint, int width, int maxLines, boolean ellipsize) {
        if (text == null) text = "";
        if (width < 1) width = 1;
        String key = text + "|" + paint.getTextSize() + "|" + paint.getTypeface() + "|" + width + "|" + maxLines + "|" + paint.getColor();
        StaticLayout layout = cache.get(key);
        if (layout != null) return layout;

        StaticLayout.Builder builder = StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setMaxLines(maxLines)
                .setLineSpacing(0, 1f)
                .setIncludePad(false);
        if (ellipsize) {
            builder.setEllipsize(TextUtils.TruncateAt.END);
        }
        builder.setAlignment(Layout.Alignment.ALIGN_NORMAL);
        layout = builder.build();
        cache.put(key, layout);
        return layout;
    }

    public void clear() {
        cache.clear();
    }
}
