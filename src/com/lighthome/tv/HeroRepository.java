package com.lighthome.tv;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * UI-process-side reader for hero.json. This class NEVER opens a network socket --
 * all fetching happens in HeroSyncService, which runs in the ":sync" process. We only
 * read the file it already wrote and decode (at most 2) local bitmap files.
 */
public final class HeroRepository {

    public static final long MAX_AGE_MS = 6L * 60 * 60 * 1000; // 6 hours
    public static final String ACTION_HERO_UPDATED = "com.lighthome.tv.HERO_UPDATED";

    private final Context appContext;
    private final File heroDir;
    private final File jsonFile;
    private long generatedAt = 0L;

    public HeroRepository(Context context) {
        this.appContext = context.getApplicationContext();
        this.heroDir = new File(appContext.getFilesDir(), "hero");
        this.jsonFile = new File(heroDir, "hero.json");
    }

    public File getHeroDir() {
        return heroDir;
    }

    public long getGeneratedAt() {
        return generatedAt;
    }

    public boolean isStale() {
        return !jsonFile.exists() || (System.currentTimeMillis() - generatedAt) > MAX_AGE_MS;
    }

    /** Synchronous file read; call from Bg. Returns an empty list if there's no data yet. */
    public List<HeroItem> loadItems() {
        List<HeroItem> result = new ArrayList<>();
        if (!jsonFile.exists()) return result;
        try {
            byte[] bytes = readAll(jsonFile);
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            generatedAt = root.optLong("generatedAt", 0L);
            JSONArray items = root.optJSONArray("items");
            if (items == null) return result;
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.getJSONObject(i);
                result.add(new HeroItem(
                        o.optString("title", ""),
                        o.optString("overview", ""),
                        o.optString("metaLine", ""),
                        o.optString("providerLabel", ""),
                        o.optString("providerPackage", ""),
                        o.optInt("providerAccent", 0xFFFFFFFF),
                        o.optBoolean("priority", false),
                        o.optString("backdropFile", null)));
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    /** Decode a backdrop file scaled to at most maxW x maxH, RGB_565. Call off the UI thread. */
    public Bitmap decodeBackdrop(HeroItem item, int maxW, int maxH) {
        if (item == null || item.backdropFile == null) return null;
        if (item.backdropFile.contains("/") || item.backdropFile.contains("..")) return null;
        File f = new File(heroDir, item.backdropFile);
        if (!f.exists()) return null;
        return ImageUtils.decodeSampled(f.getAbsolutePath(), maxW, maxH);
    }

    public boolean isProviderInstalled(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            appContext.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            int n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) {
                off += n;
            }
            return buf;
        } finally {
            in.close();
        }
    }
}
