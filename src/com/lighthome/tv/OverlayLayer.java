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
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Modal chrome drawn above everything else: the Inputs drawer, the Settings / Hidden
 * apps / Hold-OK glass sheets, the move-mode hint bar, and the hide/undo toast.
 * HomeView owns all navigation; this class only reads plain state and draws it.
 */
public final class OverlayLayer extends View implements MotionClock.Ticker {

    private static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private float scale = 1f;
    private final TextLayoutCache textCache = new TextLayoutCache();
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shapePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rScratch = new RectF();
    private final RectF rRow = new RectF();
    private final Matrix scratchMatrix = new Matrix();

    private Shader glassShader;

    private final Spring drawerProgress = new Spring(0f, Motion.SHEET_STIFFNESS);
    private final Spring sheetProgress = new Spring(0f, Motion.SHEET_STIFFNESS);
    private final Spring toastAlpha = new Spring(0f, Motion.TOAST_STIFFNESS);

    public OverlayType overlay = OverlayType.NONE;

    // Inputs drawer state
    public List<String> inputLabels = new ArrayList<>();
    public List<String> inputStatus = new ArrayList<>();
    public List<String> inputIcons = new ArrayList<>(); // IconCache.* name per row, by TvInputInfo type
    public int inputIndex = 0;
    public boolean inputsNoneFound = false;
    public boolean inputsAllDisconnected = false;

    // Settings / Hidden apps / Hold menu sheet state
    public List<String> sheetLabels = new ArrayList<>();
    public List<Integer> sheetColors = new ArrayList<>();
    public List<String> sheetIcons = new ArrayList<>(); // IconCache.* name per row, or null for none
    public int sheetIndex = 0;
    public String sheetHeaderTitle = null; // non-null only for the hold-OK menu
    public android.graphics.Shader sheetHeaderIconShader; // hold-OK menu only: the target app's cached tile shader
    public int sheetHeaderIconW, sheetHeaderIconH; // source bitmap size backing sheetHeaderIconShader
    public final RectF sheetAnchor = new RectF();
    public String emptyMessage = null; // e.g. "No hidden apps"

    // Toast
    public boolean toastWanted = false;
    public String toastText = "";

    // Move hint bar
    public boolean moveHintVisible = false;

    public OverlayLayer(Context context) {
        super(context);
        setWillNotDraw(false);
    }

    public void setScale(float s) { scale = s; }

    public void setGlassSource(Bitmap src) {
        if (src != null) {
            glassShader = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setScale(Metrics.CANVAS_W / (float) src.getWidth(), Metrics.CANVAS_H / (float) src.getHeight());
            glassShader.setLocalMatrix(m);
        } else {
            glassShader = null;
        }
    }

    public void setOverlay(OverlayType type) {
        overlay = type;
        if (type == OverlayType.INPUTS) {
            drawerProgress.setTarget(1f);
        } else {
            drawerProgress.setTarget(0f);
        }
        if (type == OverlayType.SETTINGS || type == OverlayType.HIDDEN_APPS || type == OverlayType.HOLD_MENU) {
            sheetProgress.setTarget(1f);
        } else {
            sheetProgress.setTarget(0f);
        }
        invalidate();
    }

    /** Switches sheet content without re-running the open animation (e.g. Settings -> Hidden apps). */
    public void swapSheetContentInPlace() {
        invalidate();
    }

    public void showToast(String text) {
        toastWanted = true;
        toastText = text;
        toastAlpha.setTarget(1f);
        invalidate();
    }

    public void dismissToast() {
        toastWanted = false;
        toastAlpha.setTarget(0f);
    }

    public boolean isAnyModalOpen() {
        return overlay == OverlayType.INPUTS || overlay == OverlayType.SETTINGS
                || overlay == OverlayType.HIDDEN_APPS || overlay == OverlayType.HOLD_MENU;
    }

    @Override
    public boolean tick(float dt) {
        boolean moving = false;
        if (drawerProgress.step(dt)) moving = true;
        if (sheetProgress.step(dt)) moving = true;
        if (toastAlpha.step(dt)) moving = true;
        if (moving) invalidate();
        return moving;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.scale(scale, scale);

        if (drawerProgress.getValue() > 0.001f) {
            drawInputsDrawer(canvas);
        }
        if (sheetProgress.getValue() > 0.001f && (overlay == OverlayType.SETTINGS
                || overlay == OverlayType.HIDDEN_APPS || overlay == OverlayType.HOLD_MENU)) {
            drawSheet(canvas);
        }
        if (moveHintVisible) {
            drawMoveHintBar(canvas);
        }
        if (toastAlpha.getValue() > 0.001f) {
            drawToast(canvas);
        }

        canvas.restore();
    }

    private void drawGlassRect(Canvas canvas, RectF rect, float radius, int fillAlpha) {
        if (glassShader != null) {
            shapePaint.setStyle(Paint.Style.FILL);
            shapePaint.setShader(glassShader);
            shapePaint.setAlpha(255);
            canvas.drawRoundRect(rect, radius, radius, shapePaint);
            shapePaint.setShader(null);
        }
        shapePaint.setStyle(Paint.Style.FILL);
        shapePaint.setColor(Metrics.COLOR_GLASS_FILL);
        shapePaint.setAlpha(Math.round((Metrics.COLOR_GLASS_FILL >>> 24) / 255f * fillAlpha));
        canvas.drawRoundRect(rect, radius, radius, shapePaint);
        shapePaint.setAlpha(255);
        shapePaint.setStyle(Paint.Style.STROKE);
        shapePaint.setStrokeWidth(1f);
        shapePaint.setColor(Metrics.COLOR_GLASS_STROKE);
        canvas.drawRoundRect(rect, radius, radius, shapePaint);
    }

    private void drawInputsDrawer(Canvas canvas) {
        float progress = drawerProgress.getValue();
        float drawerLeft = Metrics.CANVAS_W - Metrics.DRAWER_W + (1f - progress) * Metrics.DRAWER_W;
        rScratch.set(drawerLeft, 0, drawerLeft + Metrics.DRAWER_W, Metrics.CANVAS_H);

        shapePaint.setStyle(Paint.Style.FILL);
        shapePaint.setColor(Metrics.SHEET_FILL_COLOR);
        canvas.drawRoundRect(rScratch, Metrics.DRAWER_RADIUS, Metrics.DRAWER_RADIUS, shapePaint);
        if (glassShader != null) {
            shapePaint.setShader(glassShader);
            shapePaint.setAlpha(140);
            canvas.drawRoundRect(rScratch, Metrics.DRAWER_RADIUS, Metrics.DRAWER_RADIUS, shapePaint);
            shapePaint.setShader(null);
            shapePaint.setAlpha(255);
        }

        float contentLeft = drawerLeft + Metrics.DRAWER_PAD_LEFT;
        float contentRight = Metrics.CANVAS_W - Metrics.DRAWER_PAD_RIGHT;
        float cursorY = Metrics.DRAWER_PAD_TOP;

        textPaint.setTypeface(MEDIUM);
        textPaint.setTextSize(Metrics.DRAWER_TITLE_SIZE);
        textPaint.setColor(Metrics.COLOR_TEXT);
        canvas.drawText("Inputs", contentLeft, cursorY + Metrics.DRAWER_TITLE_SIZE, textPaint);
        cursorY += Metrics.DRAWER_TITLE_SIZE + 36;

        if (inputsNoneFound) {
            textPaint.setTypeface(REGULAR);
            textPaint.setTextSize(Metrics.DRAWER_HELPER_SIZE);
            textPaint.setColor(Metrics.COLOR_TEXT_70);
            canvas.drawText("No inputs found", contentLeft, cursorY + Metrics.DRAWER_HELPER_SIZE, textPaint);
            return;
        }

        for (int i = 0; i < inputLabels.size(); i++) {
            boolean focused = i == inputIndex;
            rRow.set(contentLeft, cursorY, contentRight, cursorY + Metrics.DRAWER_ROW_H);
            if (focused) {
                shapePaint.setStyle(Paint.Style.FILL);
                shapePaint.setColor(Metrics.COLOR_TEXT);
                canvas.drawRoundRect(rRow, Metrics.DRAWER_ROW_RADIUS, Metrics.DRAWER_ROW_RADIUS, shapePaint);
            }
            int textColor = focused ? Metrics.COLOR_GROUND : Metrics.COLOR_TEXT;

            float iconCx = rRow.left + Metrics.DRAWER_ICON_SIZE / 2f + 4;
            float iconCy = rRow.centerY();
            shapePaint.setStyle(Paint.Style.FILL);
            shapePaint.setColor(focused ? 0x14101014 : Metrics.COLOR_GLASS_FILL);
            rScratch.set(iconCx - Metrics.DRAWER_ICON_SIZE / 2f, iconCy - Metrics.DRAWER_ICON_SIZE / 2f,
                    iconCx + Metrics.DRAWER_ICON_SIZE / 2f, iconCy + Metrics.DRAWER_ICON_SIZE / 2f);
            canvas.drawRoundRect(rScratch, Metrics.DRAWER_ICON_RADIUS, Metrics.DRAWER_ICON_RADIUS, shapePaint);
            // Dark when the row's focused (white pill behind it), white otherwise --
            // matches the row-state coloring every other glyph in this drawer follows.
            String iconName = i < inputIcons.size() ? inputIcons.get(i) : IconCache.INPUT;
            IconCache.draw(canvas, iconName, iconCx, iconCy, 24f, textColor);

            textPaint.setTypeface(MEDIUM);
            textPaint.setTextSize(Metrics.DRAWER_ROW_LABEL_SIZE);
            textPaint.setColor(textColor);
            canvas.drawText(inputLabels.get(i), iconCx + Metrics.DRAWER_ICON_SIZE / 2f + 14,
                    rRow.centerY() + Metrics.DRAWER_ROW_LABEL_SIZE * 0.35f, textPaint);

            String status = i < inputStatus.size() ? inputStatus.get(i) : "";
            textPaint.setTypeface(REGULAR);
            textPaint.setTextSize(Metrics.DRAWER_STATUS_SIZE);
            textPaint.setColor(focused ? 0x99101014 : Metrics.COLOR_TEXT_70);
            float statusW = textPaint.measureText(status);
            canvas.drawText(status, contentRight - statusW, rRow.centerY() + Metrics.DRAWER_STATUS_SIZE * 0.35f, textPaint);

            cursorY += Metrics.DRAWER_ROW_H + Metrics.DRAWER_ROW_GAP;
        }

        if (inputsAllDisconnected) {
            cursorY += 12;
            textPaint.setTypeface(REGULAR);
            textPaint.setTextSize(Metrics.DRAWER_HELPER_SIZE);
            textPaint.setColor(Metrics.COLOR_TEXT_70);
            StaticLayout layout = textCache.get("No devices detected. Check the HDMI cable.", textPaint,
                    (int) (contentRight - contentLeft), 2, false);
            canvas.save();
            canvas.translate(contentLeft, cursorY);
            layout.draw(canvas);
            canvas.restore();
        }
    }

    private void drawSheet(Canvas canvas) {
        float p = sheetProgress.getValue();
        float rowCount = sheetLabels.isEmpty() ? 1 : sheetLabels.size();
        float headerH = sheetHeaderTitle != null ? 96f : 0f;
        float h = Metrics.SHEET_PAD * 2 + headerH + rowCount * Metrics.SHEET_ROW_H
                + Math.max(0, sheetLabels.size() - 1) * Metrics.SHEET_ROW_GAP;
        if (sheetLabels.isEmpty() && emptyMessage != null) h = Metrics.SHEET_PAD * 2 + 48f;

        float cx = sheetAnchor.centerX();
        float cy = sheetAnchor.top;
        rScratch.set(sheetAnchor.left, sheetAnchor.top, sheetAnchor.left + Metrics.SHEET_W, sheetAnchor.top + h);

        canvas.save();
        canvas.scale(p, p, cx, cy + 10);
        shapePaint.setStyle(Paint.Style.FILL);
        shapePaint.setColor(Metrics.SHEET_FILL_COLOR);
        canvas.drawRoundRect(rScratch, Metrics.SHEET_RADIUS, Metrics.SHEET_RADIUS, shapePaint);

        float cursorY = rScratch.top + Metrics.SHEET_PAD;
        float left = rScratch.left + Metrics.SHEET_PAD;
        float right = rScratch.right - Metrics.SHEET_PAD;

        if (sheetHeaderTitle != null) {
            rScratch.set(left, cursorY, left + 56, cursorY + 56);
            if (sheetHeaderIconShader != null) {
                // Real app icon, reusing the AppInfo's already-built tile shader (no new
                // bitmap allocated here) -- center-cropped (uniform scale, not stretched)
                // into the 56x56 rounded square.
                float sx = rScratch.width() / (float) sheetHeaderIconW;
                float sy = rScratch.height() / (float) sheetHeaderIconH;
                float s = Math.max(sx, sy);
                float cropW = rScratch.width() / s, cropH = rScratch.height() / s;
                float srcLeft = (sheetHeaderIconW - cropW) / 2f, srcTop = (sheetHeaderIconH - cropH) / 2f;
                scratchMatrix.reset();
                scratchMatrix.setScale(s, s);
                scratchMatrix.postTranslate(rScratch.left - srcLeft * s, rScratch.top - srcTop * s);
                sheetHeaderIconShader.setLocalMatrix(scratchMatrix);
                shapePaint.setStyle(Paint.Style.FILL);
                shapePaint.setShader(sheetHeaderIconShader);
                canvas.drawRoundRect(rScratch, 14, 14, shapePaint);
                shapePaint.setShader(null);
            } else {
                shapePaint.setStyle(Paint.Style.FILL);
                shapePaint.setColor(Metrics.COLOR_GLASS_FILL);
                canvas.drawRoundRect(rScratch, 14, 14, shapePaint);
                IconCache.draw(canvas, IconCache.INFO, rScratch.centerX(), rScratch.centerY(), 28f, Metrics.COLOR_TEXT);
            }
            textPaint.setTypeface(MEDIUM);
            textPaint.setTextSize(24);
            textPaint.setColor(Metrics.COLOR_TEXT);
            canvas.drawText(sheetHeaderTitle, left + 72, cursorY + 36, textPaint);
            cursorY += headerH;
        }

        if (sheetLabels.isEmpty()) {
            textPaint.setTypeface(REGULAR);
            textPaint.setTextSize(22);
            textPaint.setColor(Metrics.COLOR_TEXT_70);
            canvas.drawText(emptyMessage != null ? emptyMessage : "", left, cursorY + 30, textPaint);
        } else {
            for (int i = 0; i < sheetLabels.size(); i++) {
                boolean focused = i == sheetIndex;
                rRow.set(left, cursorY, right, cursorY + Metrics.SHEET_ROW_H);
                if (focused) {
                    shapePaint.setStyle(Paint.Style.FILL);
                    shapePaint.setColor(Metrics.COLOR_TEXT);
                    canvas.drawRoundRect(rRow, Metrics.SHEET_ROW_RADIUS, Metrics.SHEET_ROW_RADIUS, shapePaint);
                }
                int baseColor = i < sheetColors.size() ? sheetColors.get(i) : Metrics.COLOR_TEXT;
                int textColor = focused ? Metrics.COLOR_GROUND : baseColor;
                String iconName = i < sheetIcons.size() ? sheetIcons.get(i) : null;
                float textLeft = rRow.left + 20;
                if (iconName != null) {
                    IconCache.draw(canvas, iconName, rRow.left + 32, rRow.centerY(), 24f, textColor);
                    textLeft = rRow.left + 56;
                }
                textPaint.setTypeface(MEDIUM);
                textPaint.setTextSize(22);
                textPaint.setColor(textColor);
                canvas.drawText(sheetLabels.get(i), textLeft, rRow.centerY() + 8, textPaint);
                cursorY += Metrics.SHEET_ROW_H + Metrics.SHEET_ROW_GAP;
            }
        }
        canvas.restore();
    }

    private void drawMoveHintBar(Canvas canvas) {
        // No Unicode arrow glyphs (tofu risk on the system font) -- a small 4-direction
        // arrow cluster drawn with the cached Path below stands in for "arrow keys".
        String text = " to move  ·  OK to drop  ·  Back to cancel";
        textPaint.setTypeface(MEDIUM);
        textPaint.setTextSize(Metrics.HINT_BAR_FONT);
        textPaint.setColor(0xE6FFFFFF);
        float textW = textPaint.measureText(text);
        float iconSpace = 36f;
        float w = iconSpace + textW + Metrics.HINT_BAR_PAD_H * 2;
        float h = Metrics.HINT_BAR_FONT + Metrics.HINT_BAR_PAD_V * 2;
        float left = (Metrics.CANVAS_W - w) / 2f;
        float top = Metrics.CANVAS_H - Metrics.HINT_BAR_BOTTOM - h;
        rScratch.set(left, top, left + w, top + h);
        drawGlassRect(canvas, rScratch, h / 2f, 255);

        float iconCx = left + Metrics.HINT_BAR_PAD_H + 12;
        float iconCy = top + h / 2f;
        IconCache.draw(canvas, IconCache.OPEN_WITH, iconCx, iconCy, 24f, 0xE6FFFFFF);

        canvas.drawText(text, left + Metrics.HINT_BAR_PAD_H + iconSpace, top + h / 2f + Metrics.HINT_BAR_FONT * 0.35f, textPaint);
    }

    private void drawToast(Canvas canvas) {
        float a = toastAlpha.getValue();
        int alpha255 = Math.round(Math.max(0, Math.min(1, a)) * 255);
        textPaint.setTypeface(MEDIUM);
        textPaint.setTextSize(Metrics.TOAST_FONT);
        textPaint.setColor((alpha255 << 24) | (Metrics.COLOR_TEXT & 0x00FFFFFF));
        float textW = textPaint.measureText(toastText);
        float w = textW + Metrics.TOAST_PAD_H * 2;
        float h = Metrics.TOAST_FONT + Metrics.TOAST_PAD_V * 2;
        float left = (Metrics.CANVAS_W - w) / 2f;
        float top = Metrics.CANVAS_H - Metrics.TOAST_BOTTOM - h;
        rScratch.set(left, top, left + w, top + h);

        shapePaint.setStyle(Paint.Style.FILL);
        shapePaint.setColor((Math.round(alpha255 * 0.95f) << 24) | (Metrics.SHEET_FILL_COLOR & 0x00FFFFFF));
        canvas.drawRoundRect(rScratch, h / 2f, h / 2f, shapePaint);

        canvas.drawText(toastText, left + Metrics.TOAST_PAD_H, top + h / 2f + Metrics.TOAST_FONT * 0.35f, textPaint);
    }
}
