package com.lighthome.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.format.DateFormat;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Draws the hero text, the top bar, "Your apps" + the whole tile grid, all on one
 * Canvas. Everything below the top bar is drawn inside one canvas.translate() so the
 * hero->grid page shift and per-row scrolling are a single transform, not per-element
 * layout. Tile bitmaps are drawn with a BitmapShader cached on each AppInfo (built once,
 * not here) so onDraw never allocates a shader.
 */
public final class ContentLayer extends View implements MotionClock.Ticker {

    private static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private float scale = 1f;
    private final TextLayoutCache textCache = new TextLayoutCache();
    private final TileFlow tileFlow = new TileFlow();

    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shapePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix scratchMatrix = new Matrix();
    private final RectF rScratch = new RectF();
    private final RectF rTopbarInputs = new RectF();
    private final RectF rTopbarSettings = new RectF();
    private final RectF rTopbarHomeOptions = new RectF();
    private final RectF rHeroCta = new RectF();

    // Icon Paths -- built once here and reused every frame (translated/scaled via the
    // canvas, never rebuilt with new Path()/moveTo() calls per draw). Local coordinate
    // space is centered on (0,0) at roughly the on-screen icon's own scale.
    private final android.graphics.DashPathEffect ghostDash = new android.graphics.DashPathEffect(new float[]{8f, 6f}, 0f);

    private Bitmap glassSource;
    private Shader glassShader;

    private List<AppInfo> apps = java.util.Collections.emptyList();

    private HeroItem heroItem;
    private boolean heroInstalled;
    private int heroIndex;
    private int heroTotal;
    private String ctaText = "";

    private float gridProgress = 0f; // 0 = hero, 1 = fully in grid
    private float translateY = Metrics.GRID_SCROLL_SHIFT; // design px, applied to the label+grid group

    private final Spring heroCtaScale = new Spring(1f, Motion.FOCUS_SCALE_STIFFNESS);
    private boolean heroFocused = false;
    private boolean heroPressed = false;

    private int focusedGridIndex = -1;
    private final Spring enteringScale = new Spring(1f, Motion.FOCUS_SCALE_STIFFNESS);
    private final Spring leavingScale = new Spring(1f, Motion.FOCUS_SCALE_STIFFNESS);
    private final Spring labelAlpha = new Spring(0f, Motion.LABEL_FADE_STIFFNESS);
    private int enteringIndex = -1;
    private int leavingIndex = -1;
    private boolean gridPressed = false;

    private int moveModeIndex = -1;
    private String moveModePackage = null;
    private float moveCenterX, moveCenterY;
    private int holdMenuHeldIndex = -1;

    private boolean is24h;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, d MMM", Locale.getDefault());
    private long lastMinuteBucket = -1;
    private String clockText = "";
    private String dateText = "";

    public ContentLayer(Context context) {
        super(context);
        setWillNotDraw(false);
        is24h = DateFormat.is24HourFormat(context);
    }

    // ---- setters called by HomeView on state changes ----

    public void setScale(float s) { scale = s; invalidate(); }

    public void setGlassSource(Bitmap src) {
        glassSource = src;
        if (src != null) {
            glassShader = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setScale(Metrics.CANVAS_W / (float) src.getWidth(), Metrics.CANVAS_H / (float) src.getHeight());
            glassShader.setLocalMatrix(m);
        } else {
            glassShader = null;
        }
        invalidate();
    }

    public void setApps(List<AppInfo> newApps, boolean animateReorder) {
        apps = newApps;
        tileFlow.sync(apps, animateReorder);
        invalidate();
    }

    public void setHero(HeroItem item, boolean installed, int index, int total) {
        heroItem = item;
        heroInstalled = installed;
        heroIndex = index;
        heroTotal = total;
        ctaText = item == null ? "" : (installed ? "Open in " + item.providerLabel : "Get " + item.providerLabel);
        invalidate();
    }

    public void setGridFocusIndex(int idx) {
        if (idx == focusedGridIndex) return;
        if (focusedGridIndex >= 0) {
            leavingIndex = focusedGridIndex;
            leavingScale.setStiffness(Motion.FOCUS_SCALE_STIFFNESS);
            leavingScale.snapTo(enteringScale.getValue());
            leavingScale.setTarget(1f);
        }
        focusedGridIndex = idx;
        enteringIndex = idx;
        if (idx >= 0) {
            enteringScale.setStiffness(Motion.FOCUS_SCALE_STIFFNESS);
            enteringScale.snapTo(1f);
            enteringScale.setTarget(1.08f);
            labelAlpha.snapTo(0f);
            labelAlpha.setTarget(1f);
        } else {
            labelAlpha.setTarget(0f);
        }
        invalidate();
    }

    public void setHeroFocused(boolean focused) {
        heroFocused = focused;
        heroCtaScale.setStiffness(Motion.FOCUS_SCALE_STIFFNESS);
        heroCtaScale.setTarget(focused ? Metrics.HERO_CTA_FOCUS_SCALE : 1f);
    }

    public void setGridPressed(boolean pressed) {
        gridPressed = pressed;
        if (enteringIndex >= 0) {
            enteringScale.setStiffness(pressed ? Motion.PRESS_DOWN_STIFFNESS : Motion.PRESS_UP_STIFFNESS);
            enteringScale.setTarget(pressed ? Motion.PRESS_SCALE : 1.08f);
        }
    }

    public void setHeroPressed(boolean pressed) {
        heroPressed = pressed;
        heroCtaScale.setStiffness(pressed ? Motion.PRESS_DOWN_STIFFNESS : Motion.PRESS_UP_STIFFNESS);
        heroCtaScale.setTarget(pressed ? Motion.PRESS_SCALE : (heroFocused ? Metrics.HERO_CTA_FOCUS_SCALE : 1f));
    }

    /**
     * movingPackage is the source of truth for WHICH app the floating tile draws; the
     * index is only used to place the ghost slot in the grid. Previously the floating
     * tile was drawn via apps.get(moveModeIndex), and HomeView updated the index as the
     * tile moved but the grid's package order had already changed underneath it by then
     * -- the two were briefly inconsistent every step, so the floating tile could show
     * whatever app the reorder had just moved INTO the old index. Keying the floating
     * tile strictly by package removes that class of bug entirely.
     */
    public void setMoveMode(int movingLinearIndex, String movingPackage) {
        moveModeIndex = movingLinearIndex;
        moveModePackage = movingPackage;
        invalidate();
    }

    public void setMoveFloatingCenter(float cx, float cy) {
        moveCenterX = cx;
        moveCenterY = cy;
    }

    public void setHoldMenuHeld(int idx) {
        holdMenuHeldIndex = idx;
        invalidate();
    }

    public void setProgress(float progress, float translateYDesignPx) {
        gridProgress = progress;
        translateY = translateYDesignPx;
        invalidate();
    }

    public TileFlow getTileFlow() { return tileFlow; }

    // ---- pure layout queries used by HomeView for focus-ring targeting ----

    public RectF getTileRect(int index, RectF out) {
        int row = index / Metrics.COLS;
        int col = index % Metrics.COLS;
        float x = Metrics.tileLeft(col);
        float y = Metrics.tileTop(row);
        out.set(x, y, x + Metrics.TILE_W, y + Metrics.TILE_H);
        return out;
    }

    public RectF getHeroCtaRect(RectF out) {
        out.set(rHeroCta);
        return out;
    }

    public RectF getTopbarInputsRect(RectF out) {
        out.set(rTopbarInputs);
        return out;
    }

    public RectF getTopbarSettingsRect(RectF out) {
        out.set(rTopbarSettings);
        return out;
    }

    public RectF getTopbarHomeOptionsRect(RectF out) {
        out.set(rTopbarHomeOptions);
        return out;
    }

    public float liveGridTileScale(int index) {
        if (index == enteringIndex) return enteringScale.getValue();
        if (index == leavingIndex) return leavingScale.getValue();
        return 1f;
    }

    public float liveHeroCtaScale() {
        return heroCtaScale.getValue();
    }

    @Override
    public boolean tick(float dt) {
        boolean moving = false;
        if (tileFlow.tick(dt)) moving = true;
        if (enteringScale.step(dt)) moving = true;
        if (leavingScale.step(dt)) moving = true;
        if (heroCtaScale.step(dt)) moving = true;
        if (labelAlpha.step(dt)) moving = true;
        if (moving) invalidate();
        return moving;
    }

    // ---- drawing ----

    @Override
    protected void onDraw(Canvas canvas) {
        updateClockIfNeeded();
        canvas.save();
        canvas.scale(scale, scale);

        drawTopBar(canvas);
        if (gridProgress < 0.999f) {
            drawHeroText(canvas);
        }

        canvas.save();
        canvas.translate(0, translateY);
        drawYourAppsLabel(canvas);
        drawGrid(canvas);
        drawFeaturedHint(canvas);
        canvas.restore();

        canvas.restore();
    }

    private void updateClockIfNeeded() {
        long now = System.currentTimeMillis();
        long bucket = now / 60000L;
        if (bucket == lastMinuteBucket) return;
        lastMinuteBucket = bucket;
        Calendar cal = Calendar.getInstance();
        int hour24 = cal.get(Calendar.HOUR_OF_DAY);
        int minute = cal.get(Calendar.MINUTE);
        if (is24h) {
            clockText = String.format(Locale.getDefault(), "%d:%02d", hour24, minute);
        } else {
            int h12 = hour24 % 12;
            if (h12 == 0) h12 = 12;
            clockText = String.format(Locale.getDefault(), "%d:%02d", h12, minute);
        }
        dateText = dateFormat.format(cal.getTime());
    }

    private void drawTopBar(Canvas canvas) {
        textPaint.setTypeface(MEDIUM);
        textPaint.setColor(Metrics.COLOR_TEXT);
        textPaint.setTextSize(Metrics.CLOCK_SIZE);
        float clockBaseline = Metrics.TOPBAR_TOP + Metrics.CLOCK_SIZE;
        canvas.drawText(clockText, Metrics.MARGIN_X, clockBaseline, textPaint);

        textPaint.setTypeface(REGULAR);
        textPaint.setColor(Metrics.COLOR_TEXT_70);
        textPaint.setTextSize(Metrics.DATE_SIZE);
        float dateBaseline = clockBaseline + Metrics.DATE_MARGIN_TOP + Metrics.DATE_SIZE;
        canvas.drawText(dateText, Metrics.MARGIN_X, dateBaseline, textPaint);

        // Right-group buttons, right-to-left: Home options (sliders), TV Settings (gear),
        // both CIRCLE_SIZE round glass buttons, Metrics.TOPBAR_GAP (16px) apart.
        float homeOptionsRight = Metrics.CANVAS_W - Metrics.MARGIN_X;
        float homeOptionsLeft = homeOptionsRight - Metrics.CIRCLE_SIZE;
        rTopbarHomeOptions.set(homeOptionsLeft, Metrics.TOPBAR_TOP, homeOptionsRight, Metrics.TOPBAR_TOP + Metrics.CIRCLE_SIZE);
        drawGlassShape(canvas, rTopbarHomeOptions, Metrics.CIRCLE_SIZE / 2f);
        IconCache.draw(canvas, IconCache.TUNE, rTopbarHomeOptions.centerX(), rTopbarHomeOptions.centerY(), 34f, Metrics.COLOR_TEXT);

        float settingsRight = homeOptionsLeft - Metrics.TOPBAR_GAP;
        float settingsLeft = settingsRight - Metrics.CIRCLE_SIZE;
        rTopbarSettings.set(settingsLeft, Metrics.TOPBAR_TOP, settingsRight, Metrics.TOPBAR_TOP + Metrics.CIRCLE_SIZE);
        drawGlassShape(canvas, rTopbarSettings, Metrics.CIRCLE_SIZE / 2f);
        IconCache.draw(canvas, IconCache.SETTINGS, rTopbarSettings.centerX(), rTopbarSettings.centerY(), 34f, Metrics.COLOR_TEXT);

        // Inputs pill (to the left of the TV Settings circle)
        textPaint.setTypeface(MEDIUM);
        textPaint.setTextSize(Metrics.PILL_FONT);
        float inputsTextW = textPaint.measureText("Inputs");
        float iconSpace = 30f;
        float pillW = Metrics.PILL_PAD_H * 2 + iconSpace + inputsTextW;
        float pillH = Metrics.PILL_PAD_V * 2 + Metrics.PILL_FONT;
        float pillRight = settingsLeft - Metrics.TOPBAR_GAP;
        float pillLeft = pillRight - pillW;
        float pillTop = Metrics.TOPBAR_TOP + (Metrics.CIRCLE_SIZE - pillH) / 2f;
        rTopbarInputs.set(pillLeft, pillTop, pillRight, pillTop + pillH);
        drawGlassShape(canvas, rTopbarInputs, pillH / 2f);

        float iconCx = pillLeft + Metrics.PILL_PAD_H + 11;
        float iconCy = pillTop + pillH / 2f;
        IconCache.draw(canvas, IconCache.LOGIN, iconCx, iconCy, 28f, Metrics.COLOR_TEXT);

        textPaint.setColor(Metrics.COLOR_TEXT);
        canvas.drawText("Inputs", iconCx + 14, pillTop + pillH / 2f + Metrics.PILL_FONT * 0.35f, textPaint);
    }

    private void drawHeroText(Canvas canvas) {
        float alpha = 1f - gridProgress;
        if (alpha <= 0.001f) return;
        int a255 = Math.round(alpha * 255);

        if (heroItem == null) {
            return; // skeleton: backdrop only, no text
        }

        float cursorY = Metrics.HERO_TEXT_TOP;

        // Provider eyebrow + priority chip (text from AppConfig.PRIORITY_LABEL, hidden when
        // that config value is empty) -- both vertically centered on the same row,
        // with a >=16px gap between the label and the chip (v7 design-review fix: the
        // gap used to be measured WITHOUT the 0.08em letter-spacing that's actually
        // applied when the label is drawn, so wide labels like "PRIME VIDEO" ran into
        // the chip; measureLetterSpaced() below accounts for it).
        textPaint.setTypeface(MEDIUM);
        textPaint.setTextSize(Metrics.HERO_PROVIDER_SIZE);
        textPaint.setLetterSpacing(Metrics.HERO_PROVIDER_LETTER_SPACING);
        textPaint.setColor(withAlpha(heroItem.providerAccent, a255));
        String providerUpper = heroItem.providerLabel.toUpperCase(Locale.getDefault());
        float providerW = textPaint.measureText(providerUpper); // includes the letter-spacing set just above
        float rowH = Metrics.HERO_PROVIDER_SIZE * 1.3f;
        boolean showPriorityChip = heroItem.priority && !AppConfig.PRIORITY_LABEL.isEmpty();
        float eyebrowRowH = showPriorityChip ? Math.max(rowH, Metrics.HERO_CHIP_H) : rowH;
        canvas.drawText(providerUpper, Metrics.HERO_TEXT_LEFT, cursorY + eyebrowRowH / 2f + Metrics.HERO_PROVIDER_SIZE * 0.36f, textPaint);
        textPaint.setLetterSpacing(0f);

        if (showPriorityChip) {
            float chipTextW = measureWithSize(MEDIUM, Metrics.HERO_CHIP_FONT, AppConfig.PRIORITY_LABEL);
            float chipW = chipTextW + Metrics.HERO_CHIP_PAD_H * 2;
            float chipH = Metrics.HERO_CHIP_H;
            float chipLeft = Metrics.HERO_TEXT_LEFT + providerW + Metrics.HERO_EYEBROW_CHIP_GAP;
            float chipTop = cursorY + (eyebrowRowH - chipH) / 2f;
            rScratch.set(chipLeft, chipTop, chipLeft + chipW, chipTop + chipH);
            shapePaint.setStyle(Paint.Style.FILL);
            // withAlpha() REPLACES alpha rather than multiplying it, so it can't be used on
            // an already-translucent color like the chip's 12% white fill -- combine the
            // fill's own alpha with the hero's fade-in alpha by hand instead.
            int chipFillAlpha = Math.round((Metrics.HERO_CHIP_FILL_COLOR >>> 24) / 255f * a255);
            shapePaint.setColor((chipFillAlpha << 24) | (Metrics.HERO_CHIP_FILL_COLOR & 0x00FFFFFF));
            canvas.drawRoundRect(rScratch, chipH / 2f, chipH / 2f, shapePaint);
            textPaint.setTypeface(MEDIUM);
            textPaint.setTextSize(Metrics.HERO_CHIP_FONT);
            textPaint.setColor(withAlpha(0xFFFFFFFF, Math.round(a255 * 0.92f)));
            canvas.drawText(AppConfig.PRIORITY_LABEL, chipLeft + Metrics.HERO_CHIP_PAD_H, chipTop + chipH / 2f + Metrics.HERO_CHIP_FONT * 0.35f, textPaint);
        }
        cursorY += eyebrowRowH + Metrics.HERO_GAP_EYEBROW_TITLE;

        // Title: 1 line preferred, up to 2. A long title that wraps shrinks a step so
        // the block still fits within the ~800px CTA budget above "Your apps".
        textPaint.setTypeface(MEDIUM);
        textPaint.setTextSize(Metrics.HERO_TITLE_SIZE);
        textPaint.setColor(withAlpha(Metrics.COLOR_TEXT, a255));
        StaticLayout titleLayout = textCache.get(heroItem.title, textPaint, Metrics.HERO_TITLE_MAX_W, Metrics.HERO_TITLE_MAX_LINES, true);
        boolean titleWrapped = titleLayout.getLineCount() > 1;
        if (titleWrapped) {
            textPaint.setTextSize(Metrics.HERO_TITLE_SIZE_WRAPPED);
            titleLayout = textCache.get(heroItem.title, textPaint, Metrics.HERO_TITLE_MAX_W, Metrics.HERO_TITLE_MAX_LINES, true);
        }
        canvas.save();
        canvas.translate(Metrics.HERO_TEXT_LEFT, cursorY);
        titleLayout.draw(canvas);
        canvas.restore();
        cursorY += titleLayout.getHeight() + Metrics.HERO_GAP_TITLE_META;

        // Meta line
        textPaint.setTypeface(MEDIUM);
        textPaint.setTextSize(Metrics.HERO_META_SIZE);
        textPaint.setColor(withAlpha(Metrics.COLOR_TEXT_70, a255));
        canvas.drawText(heroItem.metaLine, Metrics.HERO_TEXT_LEFT, cursorY + Metrics.HERO_META_SIZE, textPaint);
        cursorY += Metrics.HERO_META_SIZE * 1.2f + Metrics.HERO_GAP_META_OVERVIEW;

        // Overview: 2 lines normally, but only 1 when a wrapped title already used the
        // extra line's worth of vertical budget.
        textPaint.setTypeface(REGULAR);
        textPaint.setTextSize(Metrics.HERO_OVERVIEW_SIZE);
        textPaint.setColor(withAlpha(0xEBFFFFFF, a255));
        int overviewMaxLines = titleWrapped ? 1 : 2;
        StaticLayout overviewLayout = textCache.get(heroItem.overview, textPaint, Metrics.HERO_OVERVIEW_MAX_W, overviewMaxLines, true);
        canvas.save();
        canvas.translate(Metrics.HERO_TEXT_LEFT, cursorY);
        overviewLayout.draw(canvas);
        canvas.restore();
        cursorY += overviewLayout.getHeight() + Metrics.HERO_GAP_OVERVIEW_CTA;

        // CTA pill (scales around its LEFT-center, like the board's transform-origin)
        textPaint.setTypeface(MEDIUM);
        textPaint.setTextSize(Metrics.HERO_CTA_FONT);
        float ctaTextW = textPaint.measureText(ctaText);
        float playIconW = 34f;
        float ctaW = Metrics.HERO_CTA_PAD_H * 2 + playIconW + ctaTextW;
        float ctaH = Metrics.HERO_CTA_PAD_V * 2 + Metrics.HERO_CTA_FONT;
        rHeroCta.set(Metrics.HERO_TEXT_LEFT, cursorY, Metrics.HERO_TEXT_LEFT + ctaW, cursorY + ctaH);

        float ctaScale = heroCtaScale.getValue();
        canvas.save();
        canvas.scale(ctaScale, ctaScale, rHeroCta.left, rHeroCta.centerY());
        shapePaint.setStyle(Paint.Style.FILL);
        shapePaint.setColor(withAlpha(0xFFFFFFFF, a255));
        canvas.drawRoundRect(rHeroCta, ctaH / 2f, ctaH / 2f, shapePaint);
        shapePaint.setPathEffect(null);

        float playCx = rHeroCta.left + Metrics.HERO_CTA_PAD_H + 10;
        float playCy = rHeroCta.centerY();
        IconCache.draw(canvas, IconCache.PLAY_ARROW, playCx, playCy, 32f, withAlpha(Metrics.COLOR_GROUND, a255));

        textPaint.setColor(withAlpha(Metrics.COLOR_GROUND, a255));
        canvas.drawText(ctaText, playCx + 22, rHeroCta.centerY() + Metrics.HERO_CTA_FONT * 0.35f, textPaint);
        canvas.restore();

        if (heroTotal > 1) {
            drawDots(canvas, a255);
        }
    }

    private void drawDots(Canvas canvas, int alpha255) {
        float dotsY = rHeroCta.centerY();
        float totalW = 0;
        for (int i = 0; i < heroTotal; i++) {
            totalW += (i == heroIndex ? Metrics.DOT_ACTIVE_W : Metrics.DOT_SIZE);
            if (i > 0) totalW += Metrics.DOT_GAP;
        }
        float x = Metrics.CANVAS_W - Metrics.DOTS_RIGHT - totalW;
        for (int i = 0; i < heroTotal; i++) {
            boolean active = i == heroIndex;
            float w = active ? Metrics.DOT_ACTIVE_W : Metrics.DOT_SIZE;
            shapePaint.setStyle(Paint.Style.FILL);
            shapePaint.setColor(withAlpha(Metrics.COLOR_TEXT, active ? alpha255 : Math.round(alpha255 * 0.4f)));
            rScratch.set(x, dotsY - Metrics.DOT_SIZE / 2f, x + w, dotsY + Metrics.DOT_SIZE / 2f);
            canvas.drawRoundRect(rScratch, Metrics.DOT_SIZE / 2f, Metrics.DOT_SIZE / 2f, shapePaint);
            x += w + Metrics.DOT_GAP;
        }
    }

    private void drawYourAppsLabel(Canvas canvas) {
        textPaint.setTypeface(MEDIUM);
        textPaint.setTextSize(Metrics.LABEL_SIZE);
        textPaint.setColor(Metrics.COLOR_TEXT);
        canvas.drawText("Your apps", Metrics.LABEL_LEFT, Metrics.LABEL_TOP_GRID + Metrics.LABEL_SIZE, textPaint);
    }

    private void drawFeaturedHint(Canvas canvas) {
        if (gridProgress <= 0.02f) return;
        int alpha = Math.round(gridProgress * 255 * 0.70f);
        float cx = Metrics.CANVAS_W / 2f;
        String label = "Featured";
        textPaint.setTypeface(MEDIUM);
        textPaint.setTextSize(Metrics.HINT_LABEL_SIZE);
        textPaint.setColor(withAlpha(Metrics.COLOR_TEXT, alpha));
        textPaint.setLetterSpacing(0.04f);
        float textW = textPaint.measureText(label);
        float textBaseline = Metrics.HINT_BOTTOM;
        canvas.drawText(label, cx - textW / 2f, textBaseline, textPaint);
        textPaint.setLetterSpacing(0f);

        float chevronY = textBaseline - Metrics.HINT_LABEL_SIZE - Metrics.HINT_GAP - 10;
        IconCache.draw(canvas, IconCache.CHEVRON_UP, cx, chevronY, Metrics.HINT_CHEVRON_SIZE, withAlpha(Metrics.COLOR_TEXT, alpha));
    }

    private void drawGrid(Canvas canvas) {
        for (int i = 0; i < apps.size(); i++) {
            if (i == moveModeIndex) {
                drawGhostSlot(canvas, i);
                continue;
            }
            drawTile(canvas, i, apps.get(i));
        }
        if (moveModeIndex >= 0 && moveModePackage != null) {
            AppInfo moving = findByPackage(moveModePackage);
            if (moving != null) drawFloatingMoveTile(canvas, moving);
        }
    }

    private AppInfo findByPackage(String pkg) {
        for (int i = 0; i < apps.size(); i++) {
            if (apps.get(i).packageName.equals(pkg)) return apps.get(i);
        }
        return null;
    }

    private void drawGhostSlot(Canvas canvas, int index) {
        AppInfo app = apps.get(index);
        float x = tileFlow.x(app.packageName, Metrics.tileLeft(index % Metrics.COLS));
        float y = tileFlow.y(app.packageName, Metrics.tileTop(index / Metrics.COLS));
        rScratch.set(x, y, x + Metrics.TILE_W, y + Metrics.TILE_H);
        shapePaint.setStyle(Paint.Style.FILL);
        shapePaint.setColor(0x08FFFFFF);
        canvas.drawRoundRect(rScratch, Metrics.TILE_RADIUS, Metrics.TILE_RADIUS, shapePaint);
        shapePaint.setStyle(Paint.Style.STROKE);
        shapePaint.setStrokeWidth(2f);
        shapePaint.setColor(0x4DFFFFFF);
        shapePaint.setPathEffect(ghostDash);
        canvas.drawRoundRect(rScratch, Metrics.TILE_RADIUS, Metrics.TILE_RADIUS, shapePaint);
        shapePaint.setPathEffect(null);
    }

    private void drawTile(Canvas canvas, int index, AppInfo app) {
        float x = tileFlow.x(app.packageName, Metrics.tileLeft(index % Metrics.COLS));
        float y = tileFlow.y(app.packageName, Metrics.tileTop(index / Metrics.COLS));
        float tileScale = liveGridTileScale(index);
        float cx = x + Metrics.TILE_W / 2f;
        float cy = y + Metrics.TILE_H / 2f;

        int alpha = 255;
        if (holdMenuHeldIndex >= 0 && index != holdMenuHeldIndex) {
            alpha = 128; // hold-OK menu: dim the rest of the grid to 50%
        } else if (moveModeIndex >= 0) {
            alpha = 179; // move mode: other tiles at 70%
        }

        rScratch.set(x, y, x + Metrics.TILE_W, y + Metrics.TILE_H);
        canvas.save();
        canvas.scale(tileScale, tileScale, cx, cy);

        bitmapPaint.setAlpha(alpha);
        if (app.tileShader != null) {
            ImageUtils.drawShaderRounded(canvas, app.tileShader, AppRepository.TILE_W, AppRepository.TILE_H,
                    rScratch, Metrics.TILE_RADIUS, bitmapPaint, scratchMatrix);
        } else {
            shapePaint.setStyle(Paint.Style.FILL);
            shapePaint.setColor(withAlpha(0xFF1C1C22, alpha));
            canvas.drawRoundRect(rScratch, Metrics.TILE_RADIUS, Metrics.TILE_RADIUS, shapePaint);
        }
        bitmapPaint.setAlpha(255);

        // 1px edge so black-banner tiles don't vanish against the ground -- skipped on
        // the focused tile, where it sits inside the ring's gap and reads as an inner line.
        if (index != focusedGridIndex) {
            shapePaint.setStyle(Paint.Style.STROKE);
            shapePaint.setStrokeWidth(1f);
            shapePaint.setColor(withAlpha(Metrics.TILE_EDGE_STROKE_COLOR, alpha));
            canvas.drawRoundRect(rScratch, Metrics.TILE_RADIUS, Metrics.TILE_RADIUS, shapePaint);
        }

        canvas.restore();

        if (index == focusedGridIndex && index == enteringIndex) {
            float scaledBottomY = cy + (Metrics.TILE_H * tileScale) / 2f;
            drawTileLabel(canvas, app.label, cx, scaledBottomY);
        }
    }

    private void drawTileLabel(Canvas canvas, String label, float cx, float scaledBottomY) {
        float a = labelAlpha.getValue();
        if (a <= 0.01f) return;
        textPaint.setTypeface(MEDIUM);
        textPaint.setTextSize(Metrics.TILE_LABEL_SIZE);
        // Cache key uses a fixed, fully-opaque color so the 100ms fade (which changes
        // alpha every frame) never busts the StaticLayout cache -- the alpha itself is
        // applied via setAlpha() below, read live by StaticLayout.draw(), after the
        // (stably-keyed) cached layout is fetched.
        textPaint.setColor(Metrics.COLOR_TEXT);
        StaticLayout layout = textCache.get(label, textPaint, Metrics.TILE_LABEL_MAX_W, 1, true);
        textPaint.setAlpha(Math.round(a * 255));
        float textW = layout.getLineWidth(0);
        canvas.save();
        canvas.translate(cx - Math.min(textW, Metrics.TILE_LABEL_MAX_W) / 2f, scaledBottomY + Metrics.TILE_LABEL_MARGIN_TOP);
        layout.draw(canvas);
        canvas.restore();
        textPaint.setAlpha(255);
    }

    private void drawFloatingMoveTile(Canvas canvas, AppInfo app) {
        float cx = moveCenterX;
        float cy = moveCenterY;
        rScratch.set(cx - Metrics.TILE_W / 2f, cy - Metrics.TILE_H / 2f, cx + Metrics.TILE_W / 2f, cy + Metrics.TILE_H / 2f);
        canvas.save();
        canvas.scale(Metrics.MOVE_SCALE, Metrics.MOVE_SCALE, cx, cy);
        if (app.tileShader != null) {
            ImageUtils.drawShaderRounded(canvas, app.tileShader, AppRepository.TILE_W, AppRepository.TILE_H,
                    rScratch, Metrics.TILE_RADIUS, bitmapPaint, scratchMatrix);
        }
        // No edge stroke here: this tile always sits under the move-mode amber ring,
        // and the stroke would read as an inner line inside the ring's gap.
        canvas.restore();
    }

    private void drawGlassShape(Canvas canvas, RectF rect, float radius) {
        if (glassShader != null) {
            shapePaint.setStyle(Paint.Style.FILL);
            shapePaint.setShader(glassShader);
            canvas.drawRoundRect(rect, radius, radius, shapePaint);
            shapePaint.setShader(null);
        }
        shapePaint.setStyle(Paint.Style.FILL);
        shapePaint.setColor(Metrics.COLOR_GLASS_FILL);
        canvas.drawRoundRect(rect, radius, radius, shapePaint);
        shapePaint.setStyle(Paint.Style.STROKE);
        shapePaint.setStrokeWidth(1f);
        shapePaint.setColor(Metrics.COLOR_GLASS_STROKE);
        canvas.drawRoundRect(rect, radius, radius, shapePaint);
    }

    private float measureWithSize(Typeface tf, float size, String text) {
        textPaint.setTypeface(tf);
        textPaint.setTextSize(size);
        return textPaint.measureText(text);
    }

    private static int withAlpha(int color, int alpha255) {
        return (Math.max(0, Math.min(255, alpha255)) << 24) | (color & 0x00FFFFFF);
    }
}
