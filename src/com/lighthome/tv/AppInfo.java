package com.lighthome.tv;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Shader;

/** One grid tile's data. The Bitmap is the ONLY thing retained from the app's resources. */
public final class AppInfo {
    public final String packageName;
    public final String label;
    public final Intent launchIntent;
    public final boolean isSystem;
    public final long lastUpdateTime;
    public boolean hidden;
    public Bitmap tileBitmap; // 336x190 RGB_565, may be null briefly while (re)rendering
    public BitmapShader tileShader; // created once alongside tileBitmap; onDraw never allocates a shader

    public AppInfo(String packageName, String label, Intent launchIntent, boolean isSystem,
                   long lastUpdateTime, boolean hidden) {
        this.packageName = packageName;
        this.label = label;
        this.launchIntent = launchIntent;
        this.isSystem = isSystem;
        this.lastUpdateTime = lastUpdateTime;
        this.hidden = hidden;
    }
}
