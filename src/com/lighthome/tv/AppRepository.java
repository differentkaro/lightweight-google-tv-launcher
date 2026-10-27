package com.lighthome.tv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Discovers launchable apps, renders each ONE tile bitmap (never retains a Drawable),
 * caches those bitmaps in memory (~3MB LruCache) and on disk (cacheDir/tiles), and
 * persists the user's order + hidden set. Every method that touches PackageManager or
 * bitmaps runs on Bg's single background thread; results come back on the main thread.
 */
public final class AppRepository {

    public static final int TILE_W = 336;
    public static final int TILE_H = 190;
    private static final int CACHE_BYTES = 3 * 1024 * 1024;

    public interface Listener {
        void onAppsUpdated(List<AppInfo> apps);
    }

    private final Context appContext;
    private final Prefs prefs;
    private final File tilesDir;
    private final LruCache<String, Bitmap> memCache;
    // Tracks which cache-key (pkg + lastUpdateTime) the cached bitmap/shader for a
    // package actually reflects, so a refresh only re-decodes/re-renders a tile when
    // that package genuinely changed -- NOT on every call (that was a real bug: every
    // resume/refresh was re-rendering every tile and orphaning the old bitmaps).
    private final Map<String, String> cacheKeyByPkg = new HashMap<>();
    private final Map<String, android.graphics.BitmapShader> shaderByPkg = new HashMap<>();

    // Generated from `default_hidden_packages=` in config.properties (build.sh/AppConfig).
    private static final Set<String> HIDDEN_BY_DEFAULT = AppConfig.DEFAULT_HIDDEN_PACKAGES;

    public AppRepository(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = new Prefs(appContext);
        this.tilesDir = new File(appContext.getCacheDir(), "tiles");
        if (!tilesDir.exists()) tilesDir.mkdirs();
        this.memCache = new LruCache<String, Bitmap>(CACHE_BYTES) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }

            @Override
            protected void entryRemoved(boolean evicted, String key, Bitmap oldValue, Bitmap newValue) {
                if (oldValue != newValue) {
                    cacheKeyByPkg.remove(key);
                    shaderByPkg.remove(key);
                }
            }
        };
    }

    public void loadAsync(final Listener listener) {
        Bg.run(new Runnable() {
            @Override
            public void run() {
                final List<AppInfo> result = scanAndRender();
                Bg.MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onAppsUpdated(result);
                    }
                });
            }
        });
    }

    public void saveOrder(List<AppInfo> apps) {
        List<String> pkgs = new ArrayList<>(apps.size());
        for (AppInfo a : apps) pkgs.add(a.packageName);
        prefs.setOrder(pkgs);
    }

    public void setHidden(String pkg, boolean hidden) {
        prefs.setHiddenApp(pkg, hidden);
    }

    /** Drops cached memory bitmaps (TRIM_MEMORY_UI_HIDDEN+). Disk cache is kept. */
    public void trimMemoryCache() {
        memCache.evictAll();
    }

    private List<AppInfo> scanAndRender() {
        PackageManager pm = appContext.getPackageManager();
        String selfPackage = appContext.getPackageName();
        Map<String, ResolveInfo> byPackage = new LinkedHashMap<>();

        Intent leanback = new Intent(Intent.ACTION_MAIN);
        leanback.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(leanback, PackageManager.MATCH_ALL)) {
            String pkg = ri.activityInfo.packageName;
            if (shouldSkip(pkg, selfPackage)) continue;
            if (!byPackage.containsKey(pkg)) byPackage.put(pkg, ri);
        }

        Intent launcher = new Intent(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)) {
            String pkg = ri.activityInfo.packageName;
            if (shouldSkip(pkg, selfPackage)) continue;
            if (byPackage.containsKey(pkg)) continue;
            ActivityInfo ai = ri.activityInfo;
            boolean hasBanner = ai.banner != 0 || ai.applicationInfo.banner != 0;
            boolean hasIcon = ai.icon != 0 || ai.applicationInfo.icon != 0;
            if (hasBanner || hasIcon) byPackage.put(pkg, ri);
        }

        Set<String> hidden = prefs.getHidden();
        List<AppInfo> apps = new ArrayList<>(byPackage.size());
        Set<String> keepFiles = new java.util.HashSet<>();

        for (Map.Entry<String, ResolveInfo> e : byPackage.entrySet()) {
            String pkg = e.getKey();
            ResolveInfo ri = e.getValue();
            ActivityInfo ai = ri.activityInfo;
            ApplicationInfo appInfo = ai.applicationInfo;
            long lastUpdate = getLastUpdateTime(pm, pkg);
            boolean isSystem = (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    || (appInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            String label = String.valueOf(ri.loadLabel(pm));
            Intent launchIntent = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launchIntent == null) launchIntent = pm.getLaunchIntentForPackage(pkg);

            String cacheKey = pkg + "_" + lastUpdate;
            keepFiles.add(cacheKey);
            Bitmap bmp = memCache.get(pkg);
            String existingKey = cacheKeyByPkg.get(pkg);
            android.graphics.BitmapShader shader;
            if (bmp == null || bmp.isRecycled() || !cacheKey.equals(existingKey)) {
                bmp = loadOrRenderBitmap(pm, ai, appInfo, label, cacheKey);
                memCache.put(pkg, bmp);
                cacheKeyByPkg.put(pkg, cacheKey);
                shader = new android.graphics.BitmapShader(bmp, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
                shaderByPkg.put(pkg, shader);
            } else {
                shader = shaderByPkg.get(pkg);
                if (shader == null) {
                    shader = new android.graphics.BitmapShader(bmp, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
                    shaderByPkg.put(pkg, shader);
                }
            }

            AppInfo info = new AppInfo(pkg, label, launchIntent, isSystem, lastUpdate, hidden.contains(pkg));
            info.tileBitmap = bmp;
            info.tileShader = shader;
            apps.add(info);
        }

        cleanupStaleFiles(keepFiles);
        applySavedOrder(apps);
        return apps;
    }

    private boolean shouldSkip(String pkg, String selfPackage) {
        return pkg.equals(selfPackage) || HIDDEN_BY_DEFAULT.contains(pkg);
    }

    private long getLastUpdateTime(PackageManager pm, String pkg) {
        try {
            return pm.getPackageInfo(pkg, 0).lastUpdateTime;
        } catch (Exception e) {
            return 0L;
        }
    }

    private Bitmap loadOrRenderBitmap(PackageManager pm, ActivityInfo ai, ApplicationInfo appInfo,
                                       String label, String cacheKey) {
        File file = new File(tilesDir, cacheKey + ".png");
        if (file.exists()) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            if (decoded != null) return decoded;
        }

        Drawable banner = null;
        try {
            banner = ai.loadBanner(pm);
            if (banner == null) banner = appInfo.loadBanner(pm);
        } catch (Throwable ignored) {
        }
        Drawable icon = null;
        if (banner == null) {
            try {
                icon = ai.loadIcon(pm);
            } catch (Throwable ignored) {
            }
        }

        Bitmap rendered = ImageUtils.renderTileBitmap(banner, icon, label, TILE_W, TILE_H);
        writeBitmapToDisk(rendered, file);
        return rendered;
    }

    private void writeBitmapToDisk(Bitmap bmp, File file) {
        try {
            FileOutputStream fos = new FileOutputStream(file);
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
        } catch (Exception ignored) {
        }
    }

    private void cleanupStaleFiles(Set<String> keepKeys) {
        File[] files = tilesDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            String key = name.endsWith(".png") ? name.substring(0, name.length() - 4) : name;
            if (!keepKeys.contains(key)) {
                f.delete();
            }
        }
    }

    private void applySavedOrder(final List<AppInfo> apps) {
        List<String> saved = prefs.getOrder();
        if (saved.isEmpty()) {
            Collections.sort(apps, new Comparator<AppInfo>() {
                @Override
                public int compare(AppInfo a, AppInfo b) {
                    return a.label.compareToIgnoreCase(b.label);
                }
            });
            saveOrder(apps);
            return;
        }
        final Map<String, Integer> rank = new HashMap<>();
        int idx = 0;
        for (String pkg : saved) rank.put(pkg, idx++);
        final int unseen = idx + 1000;
        Collections.sort(apps, new Comparator<AppInfo>() {
            @Override
            public int compare(AppInfo a, AppInfo b) {
                Integer ra = rank.containsKey(a.packageName) ? rank.get(a.packageName) : unseen;
                Integer rb = rank.containsKey(b.packageName) ? rank.get(b.packageName) : unseen;
                return ra - rb;
            }
        });
        saveOrder(apps); // persists any newly discovered apps appended at the end
    }
}
