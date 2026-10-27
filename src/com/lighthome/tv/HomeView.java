package com.lighthome.tv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The whole launcher UI: four custom-drawn layers (Backdrop, Content, Focus, Overlay)
 * plus an explicit focus/zone state machine and the one Choreographer-driven motion
 * clock that moves everything. No XML layouts, no per-tile Views, no ScrollView.
 */
public final class HomeView extends ViewGroup implements MotionClock.Ticker {

    private static final String TAG = "HomeView";
    public enum Zone { TOPBAR, HERO, GRID }

    private static final long HERO_ROTATE_MS = 10000L;
    private static final long LONG_PRESS_MS = Motion.LONG_PRESS_MS;
    private static final float OFFSET = Metrics.FOCUS_RING_OFFSET;

    private final BackdropLayer backdropLayer;
    private final ContentLayer contentLayer;
    private final FocusLayer focusLayer;
    private final OverlayLayer overlayLayer;

    private final AppRepository appRepository;
    private final HeroRepository heroRepository;
    private final Prefs prefs;
    private final MotionClock motionClock;
    private final Handler handler = Bg.MAIN;

    private float scale = 1f;
    private boolean firstAppsLoad = true;

    private final Spring scrollY = new Spring(Metrics.GRID_SCROLL_SHIFT, Motion.PAGE_STIFFNESS);

    private Zone zone = Zone.HERO;
    private int topbarIndex = 0;
    private int heroIndex = 0;
    private int gridRow = 0;
    private int gridCol = 0;

    private List<AppInfo> allApps = new ArrayList<>();
    private List<AppInfo> visibleApps = new ArrayList<>();
    private List<HeroItem> heroItems = new ArrayList<>();

    private Bitmap heroNextBitmap;
    private int heroNextIndex = -1;
    private Bitmap currentGlassSource;
    private boolean resumed = false;

    private OverlayType overlay = OverlayType.NONE;
    private int heldTileIndex = -1;
    private int moveLinearIndex = -1;
    private List<String> moveSnapshotOrder = null;
    private int moveOriginalIndex = -1;
    private List<TvInputInfo> inputsList = new ArrayList<>();

    private boolean longPressArmed = false;
    private boolean longPressTriggered = false;
    private int longPressTargetIndex = -1;

    private String toastPkg = null;
    private boolean toastConsumesOk = false;

    private final RectF rScratch = new RectF();

    private final Runnable heroRotateRunnable = new Runnable() {
        @Override public void run() {
            if (resumed && zone != null && overlay == OverlayType.NONE && !heroItems.isEmpty()) {
                showHeroSlide(heroIndex + 1);
            }
            handler.postDelayed(this, HERO_ROTATE_MS);
        }
    };

    private final Runnable longPressRunnable = new Runnable() {
        @Override public void run() {
            if (longPressArmed) {
                longPressTriggered = true;
                contentLayer.setHoldMenuHeld(-1); // clear the plain hold-dim; openHoldMenu re-applies it for the right tile
                openHoldMenu(longPressTargetIndex);
            }
        }
    };

    /**
     * Fires once, ~150ms into a held OK on a grid tile. If the key is still down at that
     * point this is clearly not a quick tap, so we switch to a subtle "being held" dim
     * (reusing the existing hold-menu dim state) instead of ever playing the press-shrink
     * animation -- that used to start immediately on key-down and looked like a
     * launch/opening animation that then got interrupted by the hold menu appearing.
     */
    private final Runnable holdIndicatorRunnable = new Runnable() {
        @Override public void run() {
            if (longPressArmed && !longPressTriggered) {
                contentLayer.setHoldMenuHeld(longPressTargetIndex);
            }
        }
    };

    private final Runnable toastDismissRunnable = new Runnable() {
        @Override public void run() {
            dismissToast();
        }
    };

    private final Runnable clockTickRunnable = new Runnable() {
        @Override public void run() {
            contentLayer.invalidate();
            handler.postDelayed(this, 15000L);
        }
    };

    public HomeView(Context context) {
        super(context);
        backdropLayer = new BackdropLayer(context);
        contentLayer = new ContentLayer(context);
        focusLayer = new FocusLayer(context);
        overlayLayer = new OverlayLayer(context);
        addView(backdropLayer);
        addView(contentLayer);
        addView(focusLayer);
        addView(overlayLayer);

        appRepository = new AppRepository(context);
        heroRepository = new HeroRepository(context);
        prefs = new Prefs(context);
        motionClock = new MotionClock(
                new MotionClock.Ticker[]{this, contentLayer, focusLayer, backdropLayer, overlayLayer},
                new Runnable() { @Override public void run() { /* layers invalidate themselves */ } });

        int savedZone = prefs.getLastZone(Zone.HERO.ordinal());
        if (savedZone >= 0 && savedZone < Zone.values().length) zone = Zone.values()[savedZone];
        gridRow = prefs.getLastGridRow();
        gridCol = prefs.getLastGridCol();
        heroIndex = prefs.getLastHeroIndex();
    }

    // ------------------------------------------------------------------
    // ViewGroup plumbing: four full-bleed children, no XML.
    // ------------------------------------------------------------------

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        int childWidthSpec = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY);
        int childHeightSpec = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(childWidthSpec, childHeightSpec);
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).layout(0, 0, r - l, b - t);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        scale = Metrics.scaleFor(w);
        backdropLayer.setScale(scale);
        contentLayer.setScale(scale);
        focusLayer.setScale(scale);
        overlayLayer.setScale(scale);
    }

    // ------------------------------------------------------------------
    // Lifecycle, driven by MainActivity
    // ------------------------------------------------------------------

    public void onResume() {
        resumed = true;
        scrollY.snapTo(zone == Zone.GRID ? Metrics.GRID_SCROLL_SHIFT : 0f);
        appRepository.loadAsync(this::onAppsLoaded);
        loadHeroAsync();
        handler.postDelayed(clockTickRunnable, 1000L);
        handler.removeCallbacks(heroRotateRunnable);
        handler.postDelayed(heroRotateRunnable, HERO_ROTATE_MS);
        motionClock.requestStart();
    }

    public void onPause() {
        resumed = false;
        handler.removeCallbacks(heroRotateRunnable);
        handler.removeCallbacks(clockTickRunnable);
        handler.removeCallbacks(longPressRunnable);
        handler.removeCallbacks(holdIndicatorRunnable);
        handler.removeCallbacks(toastDismissRunnable);
        if (overlay == OverlayType.MOVE) {
            cancelMoveMode();
        } else if (overlay != OverlayType.NONE) {
            closeOverlayImmediate();
        }
        prefs.saveFocus(zone.ordinal(), gridRow, gridCol, heroIndex);
        motionClock.stop();
    }

    public void onNewIntentHome() {
        closeOverlayImmediate();
        zone = Zone.HERO;
        scrollY.setTarget(0f);
        motionClock.requestStart();
    }

    public void onTrimMemory(int level) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            if (heroNextBitmap != null) { heroNextBitmap.recycle(); heroNextBitmap = null; heroNextIndex = -1; }
        }
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            appRepository.trimMemoryCache();
        }
    }

    public void onPackagesChanged() {
        appRepository.loadAsync(this::onAppsLoaded);
    }

    public void onHeroUpdatedBroadcast() {
        loadHeroAsync();
    }

    // ------------------------------------------------------------------
    // Apps
    // ------------------------------------------------------------------

    private void onAppsLoaded(List<AppInfo> apps) {
        String previouslyFocusedPkg = null;
        int idx = gridRow * Metrics.COLS + gridCol;
        if (idx >= 0 && idx < visibleApps.size()) previouslyFocusedPkg = visibleApps.get(idx).packageName;

        allApps = apps;
        visibleApps = new ArrayList<>();
        for (AppInfo a : apps) if (!a.hidden) visibleApps.add(a);

        boolean animate = !firstAppsLoad;
        contentLayer.setApps(visibleApps, animate);
        firstAppsLoad = false;

        if (previouslyFocusedPkg != null) {
            boolean stillThere = false;
            for (AppInfo a : visibleApps) if (a.packageName.equals(previouslyFocusedPkg)) { stillThere = true; break; }
            if (!stillThere && zone == Zone.GRID) {
                int clamped = Math.max(0, Math.min(idx, visibleApps.size() - 1));
                setGridFocusFromLinear(clamped);
            }
        }
        refreshGridFocusVisual();
        motionClock.requestStart();
    }

    // ------------------------------------------------------------------
    // Hero / TMDB (UI side: read-only, no network)
    // ------------------------------------------------------------------

    private void loadHeroAsync() {
        Bg.run(() -> {
            final List<HeroItem> items = heroRepository.loadItems();
            final boolean stale = heroRepository.isStale();
            Bg.MAIN.post(() -> onHeroLoaded(items, stale));
        });
    }

    private void onHeroLoaded(List<HeroItem> items, boolean stale) {
        boolean wasEmpty = heroItems.isEmpty();
        heroItems = items;
        if (stale) {
            HeroSyncService.scheduleIfNeeded(getContext());
        }
        if (heroItems.isEmpty()) {
            contentLayer.setHero(null, false, 0, 0);
            backdropLayer.clearHeroBitmaps();
            return;
        }
        if (heroIndex >= heroItems.size()) heroIndex = 0;
        HeroItem item = heroItems.get(heroIndex);
        boolean installed = heroRepository.isProviderInstalled(item.providerPackage);
        contentLayer.setHero(item, installed, heroIndex, heroItems.size());
        motionClock.requestStart();
        fetchAndApplyHeroBitmap(heroIndex, !wasEmpty);
    }

    private void showHeroSlide(int rawIndex) {
        if (heroItems.isEmpty()) return;
        int index = ((rawIndex % heroItems.size()) + heroItems.size()) % heroItems.size();
        heroIndex = index;
        HeroItem item = heroItems.get(index);
        boolean installed = heroRepository.isProviderInstalled(item.providerPackage);
        contentLayer.setHero(item, installed, index, heroItems.size());
        motionClock.requestStart();

        if (index == heroNextIndex && heroNextBitmap != null) {
            backdropLayer.crossfadeTo(heroNextBitmap);
            heroNextBitmap = null;
            heroNextIndex = -1;
            recomputeGlassSourceFromCurrent(item);
            motionClock.requestStart();
        } else {
            fetchAndApplyHeroBitmap(index, true);
        }
    }

    private void fetchAndApplyHeroBitmap(final int index, final boolean crossfade) {
        final HeroItem item = heroItems.get(index);
        Bg.run(() -> {
            final Bitmap full = heroRepository.decodeBackdrop(item, 1280, 720);
            final Bitmap glass = full != null ? GlassRenderer.buildGlassSource(full, 64, 36) : null;
            Bg.MAIN.post(() -> {
                if (heroIndex != index) {
                    if (full != null) full.recycle();
                    if (glass != null) glass.recycle();
                    return;
                }
                if (crossfade) backdropLayer.crossfadeTo(full); else backdropLayer.setCurrentImmediate(full);
                applyGlassSource(glass);
                motionClock.requestStart();
                preloadNextHero();
            });
        });
    }

    private void recomputeGlassSourceFromCurrent(HeroItem item) {
        Bg.run(() -> {
            final Bitmap full = heroRepository.decodeBackdrop(item, 1280, 720);
            final Bitmap glass = full != null ? GlassRenderer.buildGlassSource(full, 64, 36) : null;
            if (full != null) full.recycle();
            Bg.MAIN.post(() -> applyGlassSource(glass));
        });
    }

    private void applyGlassSource(Bitmap glass) {
        if (currentGlassSource != null) currentGlassSource.recycle();
        currentGlassSource = glass;
        contentLayer.setGlassSource(glass);
        overlayLayer.setGlassSource(glass);
    }

    private void preloadNextHero() {
        if (heroItems.size() < 2) return;
        final int next = (heroIndex + 1) % heroItems.size();
        if (next == heroNextIndex) return;
        final HeroItem item = heroItems.get(next);
        Bg.run(() -> {
            final Bitmap bmp = heroRepository.decodeBackdrop(item, 1280, 720);
            Bg.MAIN.post(() -> {
                if (heroNextBitmap != null) heroNextBitmap.recycle();
                heroNextBitmap = bmp;
                heroNextIndex = next;
            });
        });
    }

    private void resetHeroRotateTimer() {
        handler.removeCallbacks(heroRotateRunnable);
        handler.postDelayed(heroRotateRunnable, HERO_ROTATE_MS);
    }

    private void launchHeroCta() {
        if (heroItems.isEmpty()) return;
        HeroItem item = heroItems.get(heroIndex);
        boolean installed = heroRepository.isProviderInstalled(item.providerPackage);
        PackageManager pm = getContext().getPackageManager();
        try {
            if (installed) {
                Intent intent = pm.getLeanbackLaunchIntentForPackage(item.providerPackage);
                if (intent == null) intent = pm.getLaunchIntentForPackage(item.providerPackage);
                if (intent != null) getContext().startActivity(intent);
            } else {
                Intent market = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + Uri.encode(item.providerPackage)));
                market.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(market);
            }
        } catch (Exception e) {
            Log.w(TAG, "failed to launch hero CTA", e);
        }
    }

    // ------------------------------------------------------------------
    // Per-frame motion: scrollY -> dim/progress/translate, focus ring retarget
    // ------------------------------------------------------------------

    @Override
    public boolean tick(float dt) {
        boolean moving = scrollY.step(dt);
        float scrollValue = Math.max(0f, scrollY.getValue());
        float progress = clamp01(scrollValue / Metrics.GRID_SCROLL_SHIFT);
        float translate = Metrics.GRID_SCROLL_SHIFT - scrollValue;
        // Scrim rises with the content in lockstep (same spring value), but pins at the
        // p=1 position once rows scroll past the first page shift instead of following
        // further -- keeps the background consistently dark rather than scrolling away.
        float scrimShift = Math.min(scrollValue, Metrics.GRID_SCROLL_SHIFT);
        backdropLayer.setDimAlpha(progress * 0.55f);
        backdropLayer.setPageShift(scrimShift);
        contentLayer.setProgress(progress, translate);
        updateFocusRingTarget(translate);
        if (overlay == OverlayType.MOVE) {
            contentLayer.setMoveFloatingCenter(focusLayer.liveCenterX(), focusLayer.liveCenterY());
        }
        return moving;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private void updateFocusRingTarget(float translate) {
        if (overlay == OverlayType.INPUTS || overlay == OverlayType.SETTINGS || overlay == OverlayType.HIDDEN_APPS) {
            focusLayer.setVisible(false);
            return;
        }
        focusLayer.setVisible(true);

        if (overlay == OverlayType.MOVE) {
            focusLayer.setKeylineEnabled(true);
            focusLayer.setMoveMode(true);
            if (moveLinearIndex >= 0) {
                contentLayer.getTileRect(moveLinearIndex, rScratch);
                setRingTargetForTile(rScratch, translate, Metrics.MOVE_SCALE);
            }
            return;
        }
        focusLayer.setMoveMode(false);

        if (overlay == OverlayType.HOLD_MENU) {
            focusLayer.setKeylineEnabled(true);
            if (heldTileIndex >= 0) {
                contentLayer.getTileRect(heldTileIndex, rScratch);
                setRingTargetForTile(rScratch, translate, 1.08f);
            }
            return;
        }

        switch (zone) {
            case TOPBAR: {
                focusLayer.setKeylineEnabled(false);
                RectF pill = topbarIndex == 0
                        ? contentLayer.getTopbarInputsRect(rScratch)
                        : topbarIndex == 1
                                ? contentLayer.getTopbarSettingsRect(rScratch)
                                : contentLayer.getTopbarHomeOptionsRect(rScratch);
                focusLayer.retarget(pill.left - OFFSET, pill.top - OFFSET,
                        pill.width() + 2 * OFFSET, pill.height() + 2 * OFFSET, pill.height() / 2f + OFFSET);
                break;
            }
            case HERO: {
                focusLayer.setKeylineEnabled(false);
                RectF cta = contentLayer.getHeroCtaRect(rScratch);
                float s = contentLayer.liveHeroCtaScale();
                float sw = cta.width() * s;
                float sh = cta.height() * s;
                float left = cta.left;
                float top = cta.centerY() - sh / 2f;
                focusLayer.retarget(left - OFFSET, top - OFFSET, sw + 2 * OFFSET, sh + 2 * OFFSET, sh / 2f + OFFSET);
                break;
            }
            case GRID: {
                focusLayer.setKeylineEnabled(true);
                int idx = gridRow * Metrics.COLS + gridCol;
                if (idx >= 0 && idx < visibleApps.size()) {
                    contentLayer.getTileRect(idx, rScratch);
                    setRingTargetForTile(rScratch, translate, contentLayer.liveGridTileScale(idx));
                }
                break;
            }
        }
    }

    private void setRingTargetForTile(RectF canonicalRect, float translate, float tileScale) {
        float cx = canonicalRect.centerX();
        float cy = canonicalRect.centerY() + translate;
        float w = canonicalRect.width() * tileScale;
        float h = canonicalRect.height() * tileScale;
        float left = cx - w / 2f;
        float top = cy - h / 2f;
        focusLayer.retarget(left - OFFSET, top - OFFSET, w + 2 * OFFSET, h + 2 * OFFSET, Metrics.FOCUS_RING_RADIUS);
    }

    // ------------------------------------------------------------------
    // Focus/zone helpers
    // ------------------------------------------------------------------

    private int gridLinear() { return gridRow * Metrics.COLS + gridCol; }

    private int rowCount() { return (visibleApps.size() + Metrics.COLS - 1) / Metrics.COLS; }

    private int colsInRow(int row) {
        int start = row * Metrics.COLS;
        return Math.min(Metrics.COLS, visibleApps.size() - start);
    }

    private void setGridFocusFromLinear(int linear) {
        if (visibleApps.isEmpty()) { gridRow = 0; gridCol = 0; return; }
        linear = Math.max(0, Math.min(linear, visibleApps.size() - 1));
        gridRow = linear / Metrics.COLS;
        gridCol = linear % Metrics.COLS;
        refreshGridFocusVisual();
    }

    private void refreshGridFocusVisual() {
        if (zone == Zone.GRID && !visibleApps.isEmpty()) {
            contentLayer.setGridFocusIndex(gridLinear());
        } else {
            contentLayer.setGridFocusIndex(-1);
        }
    }

    private void enterZone(Zone newZone) {
        zone = newZone;
        contentLayer.setHeroFocused(newZone == Zone.HERO);
        refreshGridFocusVisual();
        scrollY.setTarget(newZone == Zone.GRID ? Metrics.GRID_SCROLL_SHIFT : 0f);
        motionClock.requestStart();
    }

    // ------------------------------------------------------------------
    // Key handling (called by MainActivity.dispatchKeyEvent)
    // ------------------------------------------------------------------

    public boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        int action = event.getAction();

        if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            resetHeroRotateTimer();
            if (toastPkg != null && code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) {
                dismissToast();
            }
        }

        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            return handleOk(action, event.getRepeatCount());
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (action == KeyEvent.ACTION_UP) return handleBack();
            return true;
        }
        if (action != KeyEvent.ACTION_DOWN) {
            return false;
        }

        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return handleDirection(-1, 0);
            case KeyEvent.KEYCODE_DPAD_RIGHT: return handleDirection(1, 0);
            case KeyEvent.KEYCODE_DPAD_UP: return handleDirection(0, -1);
            case KeyEvent.KEYCODE_DPAD_DOWN: return handleDirection(0, 1);
            default: return false;
        }
    }

    private boolean handleDirection(int dx, int dy) {
        if (overlay == OverlayType.INPUTS) return moveOverlayList(dy, inputsList.size() + 1);
        if (overlay == OverlayType.SETTINGS) return moveOverlayList(dy, overlayLayer.sheetLabels.size());
        if (overlay == OverlayType.HIDDEN_APPS) return moveOverlayList(dy, overlayLayer.sheetLabels.size());
        if (overlay == OverlayType.HOLD_MENU) return moveOverlayList(dy, overlayLayer.sheetLabels.size());
        if (overlay == OverlayType.MOVE) return moveTileInMoveMode(dx, dy);

        switch (zone) {
            case TOPBAR:
                if (dx != 0) {
                    topbarIndex = Math.max(0, Math.min(2, topbarIndex + dx));
                    motionClock.requestStart();
                    return true;
                }
                if (dy > 0) { enterZone(Zone.HERO); return true; }
                return true;
            case HERO:
                if (dx != 0) { showHeroSlide(heroIndex + dx); resetHeroRotateTimer(); return true; }
                if (dy < 0) { topbarIndex = 0; enterZone(Zone.TOPBAR); return true; }
                if (dy > 0) {
                    if (!visibleApps.isEmpty()) {
                        // DOWN from the hero always lands on the first app (row 0, col 0)
                        // -- never the last-focused grid column. That "nearest remembered
                        // column" behavior remains for every OTHER way of entering the
                        // grid (UP from the topbar isn't a thing; returning from a
                        // launched app restores the previous focus via Prefs; BACK/UP
                        // out of the grid back into the hero doesn't touch gridRow/gridCol
                        // at all, so going back down still lands on tile 0 too).
                        setGridFocusFromLinear(0);
                        enterZone(Zone.GRID);
                    }
                    return true;
                }
                return true;
            case GRID:
                if (visibleApps.isEmpty()) return true;
                if (dx != 0) {
                    int newCol = gridCol + dx;
                    if (newCol >= 0 && newCol < colsInRow(gridRow)) {
                        gridCol = newCol;
                        refreshGridFocusVisual();
                        motionClock.requestStart();
                    }
                    return true;
                }
                if (dy < 0) {
                    if (gridRow == 0) { enterZone(Zone.HERO); }
                    else {
                        gridRow--;
                        gridCol = Math.min(gridCol, colsInRow(gridRow) - 1);
                        refreshGridFocusVisual();
                        updateRowScroll();
                        motionClock.requestStart();
                    }
                    return true;
                }
                if (dy > 0) {
                    if (gridRow < rowCount() - 1) {
                        gridRow++;
                        gridCol = Math.min(gridCol, colsInRow(gridRow) - 1);
                        refreshGridFocusVisual();
                        updateRowScroll();
                        motionClock.requestStart();
                    }
                    return true;
                }
                return true;
        }
        return false;
    }

    /** Keeps the focused row at a fixed comfortable line once you scroll past row 1. */
    /**
     * Only scrolls past the base hero->grid shift once the focused row would actually
     * run off the bottom of the screen. With few rows (common case) nothing extra
     * happens; once a row overflows, each further row is pinned at the same safe
     * bottom margin -- a fixed, comfortable line, instead of growing without bound.
     */
    private void updateRowScroll() {
        float viewportBottom = Metrics.CANVAS_H - 40f;
        float rowTop = Metrics.GRID_TOP_GRID + gridRow * (float) Metrics.ROW_PITCH;
        float rowBottom = rowTop + Metrics.TILE_H;
        float rowExtra = Math.max(0f, rowBottom - viewportBottom);
        scrollY.setTarget(Metrics.GRID_SCROLL_SHIFT + rowExtra);
    }

    private boolean moveOverlayList(int dy, int count) {
        if (dy == 0 || count <= 0) return true;
        int idx = overlay == OverlayType.INPUTS ? inputsIndexField : overlayLayer.sheetIndex;
        idx = Math.max(0, Math.min(count - 1, idx + dy));
        if (overlay == OverlayType.INPUTS) {
            inputsIndexField = idx;
            overlayLayer.inputIndex = Math.min(idx, Math.max(0, inputsList.size() - 1));
        } else {
            overlayLayer.sheetIndex = idx;
        }
        overlayLayer.invalidate();
        motionClock.requestStart();
        return true;
    }

    private int inputsIndexField = 0;

    private boolean moveTileInMoveMode(int dx, int dy) {
        if (moveLinearIndex < 0) return true;
        int row = moveLinearIndex / Metrics.COLS;
        int col = moveLinearIndex % Metrics.COLS;
        int targetRow = row, targetCol = col;
        if (dx != 0) targetCol += dx;
        if (dy != 0) targetRow += dy;
        if (targetCol < 0 || targetCol >= Metrics.COLS) return true;
        int targetIndex = targetRow * Metrics.COLS + targetCol;
        if (targetIndex < 0 || targetIndex >= visibleApps.size()) return true;

        AppInfo moving = visibleApps.remove(moveLinearIndex);
        visibleApps.add(targetIndex, moving);
        moveLinearIndex = targetIndex;
        contentLayer.setApps(visibleApps, true);
        // Keep ContentLayer's floating-tile package in sync with the reordered list on
        // every step -- this used to only be set once in startMoveMode(), so the tile
        // being drawn as "floating" and the one actually under the cursor could drift
        // apart after the very first move (see ContentLayer.setMoveMode's comment).
        contentLayer.setMoveMode(moveLinearIndex, moving.packageName);
        motionClock.requestStart();
        return true;
    }

    private boolean handleOk(int action, int repeatCount) {
        if (action == KeyEvent.ACTION_DOWN) {
            if (toastPkg != null) {
                if (repeatCount == 0) undoHide();
                return true;
            }
            if (repeatCount == 0) {
                if (overlay == OverlayType.NONE && zone == Zone.GRID && !visibleApps.isEmpty()) {
                    // No press-shrink here: it used to fire the instant OK went down,
                    // which looked like a launch animation even when this turns out to
                    // be a long press -- see holdIndicatorRunnable / HOLD_INDICATOR_MS.
                    longPressArmed = true;
                    longPressTriggered = false;
                    longPressTargetIndex = gridLinear();
                    handler.postDelayed(holdIndicatorRunnable, Motion.HOLD_INDICATOR_MS);
                    handler.postDelayed(longPressRunnable, LONG_PRESS_MS);
                } else if (overlay == OverlayType.NONE && zone == Zone.HERO) {
                    contentLayer.setHeroPressed(true);
                }
            } else if (longPressArmed && !longPressTriggered) {
                // The system already confirms the key is still down (a repeat-down
                // event arrived) -- don't make a genuinely held key wait out the rest
                // of the timer on top of that.
                handler.removeCallbacks(holdIndicatorRunnable);
                handler.removeCallbacks(longPressRunnable);
                longPressRunnable.run();
            }
            return true;
        } else if (action == KeyEvent.ACTION_UP) {
            handler.removeCallbacks(holdIndicatorRunnable);
            handler.removeCallbacks(longPressRunnable);
            if (longPressArmed) {
                longPressArmed = false;
                // Clears the subtle hold-dim if we were showing it (released before the
                // long-press threshold); openHoldMenu() already re-applied it for the
                // right tile if the menu is what actually opened.
                if (!longPressTriggered) contentLayer.setHoldMenuHeld(-1);
                if (longPressTriggered) {
                    // Long press already opened the menu -- never launch, never animate
                    // a press on this key-up, just consume it.
                    longPressTriggered = false;
                    return true;
                }
            }
            contentLayer.setHeroPressed(false);
            return performOkAction();
        }
        return true;
    }

    private boolean performOkAction() {
        if (overlay == OverlayType.INPUTS) { selectInput(inputsIndexField); return true; }
        if (overlay == OverlayType.SETTINGS) { selectSettingsRow(overlayLayer.sheetIndex); return true; }
        if (overlay == OverlayType.HIDDEN_APPS) { selectHiddenAppRow(overlayLayer.sheetIndex); return true; }
        if (overlay == OverlayType.HOLD_MENU) { selectHoldMenuRow(overlayLayer.sheetIndex); return true; }
        if (overlay == OverlayType.MOVE) { dropMoveMode(); return true; }

        switch (zone) {
            case TOPBAR:
                if (topbarIndex == 0) openInputsDrawer();
                else if (topbarIndex == 1) openTvSettingsDirect();
                else openHomeOptionsSheet();
                return true;
            case HERO:
                launchHeroCta();
                return true;
            case GRID:
                if (!visibleApps.isEmpty()) launchApp(visibleApps.get(gridLinear()));
                return true;
        }
        return true;
    }

    private boolean handleBack() {
        if (overlay == OverlayType.MOVE) { cancelMoveMode(); return true; }
        if (overlay == OverlayType.HOLD_MENU) { closeHoldMenu(); return true; }
        if (overlay == OverlayType.HIDDEN_APPS) { openHomeOptionsSheet(); return true; }
        if (overlay == OverlayType.SETTINGS) { closeSettingsSheet(); return true; }
        if (overlay == OverlayType.INPUTS) { closeInputsDrawer(); return true; }

        if (zone == Zone.GRID || zone == Zone.TOPBAR) {
            enterZone(Zone.HERO);
            return true;
        }
        return true; // BACK on HERO does nothing
    }

    private void launchApp(AppInfo info) {
        if (info.launchIntent == null) return;
        try {
            getContext().startActivity(info.launchIntent);
        } catch (Exception e) {
            Log.w(TAG, "failed to launch " + info.packageName, e);
        }
    }

    // ------------------------------------------------------------------
    // Inputs drawer
    // ------------------------------------------------------------------

    private void openInputsDrawer() {
        TvInputManager tvInputManager = (TvInputManager) getContext().getSystemService(Context.TV_INPUT_SERVICE);
        inputsList = new ArrayList<>();
        if (tvInputManager != null) {
            for (TvInputInfo input : tvInputManager.getTvInputList()) {
                if (input.isPassthroughInput()) inputsList.add(input);
            }
        }
        overlayLayer.inputLabels = new ArrayList<>();
        overlayLayer.inputStatus = new ArrayList<>();
        overlayLayer.inputIcons = new ArrayList<>();
        boolean anyConnected = false;
        for (TvInputInfo input : inputsList) {
            overlayLayer.inputLabels.add(String.valueOf(input.loadLabel(getContext())));
            boolean connected = false;
            if (tvInputManager != null) {
                connected = tvInputManager.getInputState(input.getId()) == TvInputManager.INPUT_STATE_CONNECTED;
            }
            if (connected) anyConnected = true;
            overlayLayer.inputStatus.add(connected ? "Connected" : "No signal");
            // Icon is chosen from the input's TYPE_* (not its possibly user-renamed
            // label -- e.g. "Muanya's Bedro" is still an HDMI port under the hood).
            overlayLayer.inputIcons.add(iconForInputType(input.getType()));
        }
        overlayLayer.inputLabels.add("Live TV");
        overlayLayer.inputStatus.add(isPackageInstalled("com.tcl.tv") ? "Connected" : "No signal");
        overlayLayer.inputIcons.add(IconCache.LIVE_TV);

        overlayLayer.inputsNoneFound = inputsList.isEmpty() && overlayLayer.inputLabels.size() == 1 && false;
        overlayLayer.inputsNoneFound = false; // Live TV entry always present; "no inputs" means zero passthrough AND no Live TV -- Live TV always exists
        overlayLayer.inputsAllDisconnected = !anyConnected;

        inputsIndexField = 0;
        overlayLayer.inputIndex = 0;
        overlay = OverlayType.INPUTS;
        overlayLayer.setOverlay(OverlayType.INPUTS);
        focusLayer.snapToTarget();
        motionClock.requestStart();
    }

    /** Maps a TvInputInfo.TYPE_* constant to the Material icon used for that row. */
    private static String iconForInputType(int type) {
        switch (type) {
            case TvInputInfo.TYPE_HDMI:
                return IconCache.HDMI;
            case TvInputInfo.TYPE_COMPOSITE:
            case TvInputInfo.TYPE_SVIDEO:
            case TvInputInfo.TYPE_SCART:
            case TvInputInfo.TYPE_COMPONENT:
                return IconCache.COMPOSITE;
            case TvInputInfo.TYPE_TUNER:
                return IconCache.LIVE_TV;
            default:
                return IconCache.INPUT; // generic fallback for VGA/DVI/DisplayPort/other
        }
    }

    private void closeInputsDrawer() {
        overlay = OverlayType.NONE;
        overlayLayer.setOverlay(OverlayType.NONE);
        topbarIndex = 0;
        enterZone(Zone.TOPBAR);
        focusLayer.snapToTarget();
    }

    private void selectInput(int idx) {
        if (idx < inputsList.size()) {
            TvInputInfo input = inputsList.get(idx);
            try {
                Uri uri = TvContract.buildChannelUriForPassthroughInput(input.getId());
                getContext().startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (Exception e) {
                launchLiveTvFallback();
            }
        } else {
            launchLiveTvFallback();
        }
    }

    private void launchLiveTvFallback() {
        try {
            Intent intent = getContext().getPackageManager().getLaunchIntentForPackage("com.tcl.tv");
            if (intent != null) getContext().startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "live tv fallback failed", e);
        }
    }

    private boolean isPackageInstalled(String pkg) {
        try {
            getContext().getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // TV Settings (direct) / Home options sheet / Hidden apps sheet
    // ------------------------------------------------------------------

    /** TV Settings top-bar button: opens system settings directly, no sheet. */
    private void openTvSettingsDirect() {
        try { getContext().startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) {}
    }

    /** Home options top-bar button: the launcher sheet, now just Hidden apps + Refresh featured. */
    private void openHomeOptionsSheet() {
        overlayLayer.sheetLabels = new ArrayList<>(java.util.Arrays.asList("Hidden apps", "Refresh featured"));
        overlayLayer.sheetColors = new ArrayList<>(java.util.Arrays.asList(Metrics.COLOR_TEXT, Metrics.COLOR_TEXT));
        overlayLayer.sheetIcons = new ArrayList<>(java.util.Arrays.asList(IconCache.VISIBILITY_OFF, IconCache.REFRESH));
        overlayLayer.sheetHeaderTitle = null;
        overlayLayer.sheetHeaderIconShader = null;
        overlayLayer.emptyMessage = null;
        overlayLayer.sheetIndex = 0;
        computeSheetAnchorFromRect(contentLayer.getTopbarHomeOptionsRect(rScratch));
        overlay = OverlayType.SETTINGS;
        overlayLayer.setOverlay(OverlayType.SETTINGS);
        focusLayer.snapToTarget();
        motionClock.requestStart();
    }

    private void closeSettingsSheet() {
        overlay = OverlayType.NONE;
        overlayLayer.setOverlay(OverlayType.NONE);
        topbarIndex = 2; // Home options button, which opened this sheet
        enterZone(Zone.TOPBAR);
        focusLayer.snapToTarget();
    }

    private void selectSettingsRow(int idx) {
        if (idx == 0) {
            openHiddenAppsSheet();
        } else if (idx == 1) {
            HeroSyncService.scheduleIfNeeded(getContext());
            closeSettingsSheet();
        }
    }

    private void openHiddenAppsSheet() {
        List<AppInfo> hidden = new ArrayList<>();
        for (AppInfo a : allApps) if (a.hidden) hidden.add(a);
        overlayLayer.sheetLabels = new ArrayList<>();
        overlayLayer.sheetColors = new ArrayList<>();
        overlayLayer.sheetIcons = new ArrayList<>();
        for (AppInfo a : hidden) { overlayLayer.sheetLabels.add(a.label); overlayLayer.sheetColors.add(Metrics.COLOR_TEXT); overlayLayer.sheetIcons.add(null); }
        overlayLayer.emptyMessage = hidden.isEmpty() ? "No hidden apps" : null;
        overlayLayer.sheetHeaderTitle = null;
        overlayLayer.sheetHeaderIconShader = null;
        overlayLayer.sheetIndex = 0;
        overlay = OverlayType.HIDDEN_APPS;
        overlayLayer.setOverlay(OverlayType.HIDDEN_APPS);
        overlayLayer.swapSheetContentInPlace();
        motionClock.requestStart();
    }

    private void selectHiddenAppRow(int idx) {
        List<AppInfo> hidden = new ArrayList<>();
        for (AppInfo a : allApps) if (a.hidden) hidden.add(a);
        if (idx < 0 || idx >= hidden.size()) return;
        AppInfo app = hidden.get(idx);
        appRepository.setHidden(app.packageName, false);
        appRepository.loadAsync(this::onAppsLoaded);
        openHiddenAppsSheet();
    }

    private void computeSheetAnchorFromRect(RectF anchorSource) {
        float height = Metrics.SHEET_PAD * 2 + 3 * Metrics.SHEET_ROW_H + 2 * Metrics.SHEET_ROW_GAP;
        float preferredLeft = anchorSource.right + Metrics.SHEET_GAP_FROM_TILE;
        float left = preferredLeft;
        if (left + Metrics.SHEET_W > Metrics.CANVAS_W - Metrics.MARGIN_X) {
            left = anchorSource.left - Metrics.SHEET_GAP_FROM_TILE - Metrics.SHEET_W;
        }
        if (left < Metrics.MARGIN_X) left = Metrics.MARGIN_X;
        float top = anchorSource.top;
        if (top + height > Metrics.CANVAS_H - Metrics.MARGIN_X) {
            top = Metrics.CANVAS_H - Metrics.MARGIN_X - height;
        }
        overlayLayer.sheetAnchor.set(left, top, left + Metrics.SHEET_W, top + height);
    }

    // ------------------------------------------------------------------
    // Hold-OK menu
    // ------------------------------------------------------------------

    private void openHoldMenu(int linearIndex) {
        if (linearIndex < 0 || linearIndex >= visibleApps.size()) return;
        AppInfo app = visibleApps.get(linearIndex);
        heldTileIndex = linearIndex;

        List<String> rows = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        List<String> icons = new ArrayList<>();
        rows.add("Move"); colors.add(Metrics.COLOR_TEXT); icons.add(IconCache.OPEN_WITH);
        rows.add("Hide from Home"); colors.add(Metrics.COLOR_TEXT); icons.add(IconCache.VISIBILITY_OFF);
        rows.add("App info"); colors.add(Metrics.COLOR_TEXT); icons.add(IconCache.INFO);
        if (!app.isSystem) { rows.add("Uninstall"); colors.add(Metrics.COLOR_DESTRUCTIVE); icons.add(IconCache.DELETE); }
        overlayLayer.sheetLabels = rows;
        overlayLayer.sheetColors = colors;
        overlayLayer.sheetIcons = icons;
        overlayLayer.sheetHeaderTitle = app.label;
        // Real app icon in the header, reusing the already-cached tile bitmap/shader --
        // no new bitmap decode here. Falls back to a generic glyph if the tile hasn't
        // rendered yet (shader momentarily null right after install).
        overlayLayer.sheetHeaderIconShader = app.tileShader;
        overlayLayer.sheetHeaderIconW = app.tileBitmap != null ? app.tileBitmap.getWidth() : Metrics.TILE_W;
        overlayLayer.sheetHeaderIconH = app.tileBitmap != null ? app.tileBitmap.getHeight() : Metrics.TILE_H;
        overlayLayer.emptyMessage = null;
        overlayLayer.sheetIndex = 0;

        contentLayer.getTileRect(linearIndex, rScratch);
        float translate = Metrics.GRID_SCROLL_SHIFT - scrollY.getValue();
        RectF screenRect = new RectF(rScratch.left, rScratch.top + translate, rScratch.right, rScratch.bottom + translate);
        computeSheetAnchorFromRect(screenRect);

        contentLayer.setHoldMenuHeld(linearIndex);
        overlay = OverlayType.HOLD_MENU;
        overlayLayer.setOverlay(OverlayType.HOLD_MENU);
        focusLayer.snapToTarget();
        motionClock.requestStart();
    }

    private void closeHoldMenu() {
        overlay = OverlayType.NONE;
        overlayLayer.setOverlay(OverlayType.NONE);
        contentLayer.setHoldMenuHeld(-1);
        enterZone(Zone.GRID);
        focusLayer.snapToTarget();
    }

    private void selectHoldMenuRow(int idx) {
        if (heldTileIndex < 0 || heldTileIndex >= visibleApps.size()) { closeHoldMenu(); return; }
        AppInfo app = visibleApps.get(heldTileIndex);
        List<String> rows = overlayLayer.sheetLabels;
        if (idx < 0 || idx >= rows.size()) { closeHoldMenu(); return; }
        String action = rows.get(idx);
        switch (action) {
            case "Move":
                startMoveMode(heldTileIndex);
                break;
            case "Hide from Home":
                hideApp(app, heldTileIndex);
                break;
            case "App info":
                try {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.parse("package:" + app.packageName));
                    getContext().startActivity(intent);
                } catch (Exception ignored) {}
                closeHoldMenu();
                break;
            case "Uninstall":
                try {
                    Intent intent = new Intent(Intent.ACTION_DELETE);
                    intent.setData(Uri.parse("package:" + app.packageName));
                    getContext().startActivity(intent);
                } catch (Exception ignored) {}
                closeHoldMenu();
                break;
            default:
                closeHoldMenu();
        }
    }

    private void hideApp(AppInfo app, int linearIndex) {
        appRepository.setHidden(app.packageName, true);
        contentLayer.setHoldMenuHeld(-1);
        overlay = OverlayType.NONE;
        overlayLayer.setOverlay(OverlayType.NONE);
        appRepository.loadAsync(this::onAppsLoaded);
        enterZone(Zone.GRID);
        setGridFocusFromLinear(Math.min(linearIndex, Math.max(0, visibleApps.size() - 2)));
        showHideToast(app.packageName, app.label);
    }

    private void showHideToast(String pkg, String label) {
        toastPkg = pkg;
        overlayLayer.showToast("Hidden · Press OK to undo");
        handler.removeCallbacks(toastDismissRunnable);
        handler.postDelayed(toastDismissRunnable, Metrics.TOAST_DURATION_MS);
    }

    private void dismissToast() {
        toastPkg = null;
        overlayLayer.dismissToast();
        handler.removeCallbacks(toastDismissRunnable);
    }

    private void undoHide() {
        if (toastPkg == null) return;
        appRepository.setHidden(toastPkg, false);
        dismissToast();
        appRepository.loadAsync(this::onAppsLoaded);
    }

    // ------------------------------------------------------------------
    // Move mode
    // ------------------------------------------------------------------

    private void startMoveMode(int linearIndex) {
        if (linearIndex < 0 || linearIndex >= visibleApps.size()) return;
        moveSnapshotOrder = new ArrayList<>();
        for (AppInfo a : visibleApps) moveSnapshotOrder.add(a.packageName);
        moveOriginalIndex = linearIndex;
        moveLinearIndex = linearIndex;
        String movingPackage = visibleApps.get(linearIndex).packageName;

        contentLayer.setHoldMenuHeld(-1);
        overlay = OverlayType.MOVE;
        overlayLayer.setOverlay(OverlayType.MOVE);
        overlayLayer.moveHintVisible = true;
        contentLayer.setMoveMode(linearIndex, movingPackage);
        focusLayer.snapToTarget();
        motionClock.requestStart();
    }

    private void dropMoveMode() {
        appRepository.saveOrder(visibleApps);
        int finalIndex = moveLinearIndex;
        exitMoveModeCommon();
        setGridFocusFromLinear(finalIndex);
        focusLayer.snapToTarget();
    }

    private void cancelMoveMode() {
        if (moveSnapshotOrder != null) {
            List<AppInfo> restored = new ArrayList<>();
            java.util.Map<String, AppInfo> byPkg = new java.util.HashMap<>();
            for (AppInfo a : visibleApps) byPkg.put(a.packageName, a);
            for (String pkg : moveSnapshotOrder) {
                AppInfo a = byPkg.get(pkg);
                if (a != null) restored.add(a);
            }
            visibleApps = restored;
            contentLayer.setApps(visibleApps, false);
            appRepository.saveOrder(visibleApps);
        }
        int original = moveOriginalIndex;
        exitMoveModeCommon();
        setGridFocusFromLinear(original);
        focusLayer.snapToTarget();
    }

    private void exitMoveModeCommon() {
        overlay = OverlayType.NONE;
        overlayLayer.setOverlay(OverlayType.NONE);
        overlayLayer.moveHintVisible = false;
        contentLayer.setMoveMode(-1, null);
        moveLinearIndex = -1;
        moveSnapshotOrder = null;
        enterZone(Zone.GRID);
    }

    private void closeOverlayImmediate() {
        if (overlay == OverlayType.MOVE) { cancelMoveMode(); return; }
        overlay = OverlayType.NONE;
        overlayLayer.setOverlay(OverlayType.NONE);
        contentLayer.setHoldMenuHeld(-1);
        dismissToast();
    }
}
