package com.lighthome.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * The single focus-ring overlay. One set of springs (x/y/w/h/radius) retargeted every
 * frame to wherever focus currently is; the ring glides between shapes (tile, pill,
 * circle) rather than teleporting. Just a clean stroked ring -- no keyline, no shadow
 * halo -- so the gap between the tile and the ring is fully transparent.
 */
public final class FocusLayer extends View implements MotionClock.Ticker {

    private final Spring x = new Spring(0, Motion.RING_STIFFNESS);
    private final Spring y = new Spring(0, Motion.RING_STIFFNESS);
    private final Spring w = new Spring(0, Motion.RING_STIFFNESS);
    private final Spring h = new Spring(0, Motion.RING_STIFFNESS);
    private final Spring radius = new Spring(Metrics.FOCUS_RING_RADIUS, Motion.RING_STIFFNESS);

    private boolean visible = false;
    private boolean moveMode = false;
    private float scale = 1f;

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chevronPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ringRect = new RectF();
    private final Path chevronPath = new Path();

    public FocusLayer(Context context) {
        super(context);
        setWillNotDraw(false);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(Metrics.FOCUS_RING_STROKE);
        ringPaint.setColor(Metrics.COLOR_TEXT);

        chevronPaint.setStyle(Paint.Style.STROKE);
        chevronPaint.setStrokeWidth(2.4f);
        chevronPaint.setStrokeCap(Paint.Cap.ROUND);
        chevronPaint.setStrokeJoin(Paint.Join.ROUND);
        chevronPaint.setColor(Metrics.COLOR_ACCENT_MOVE);
    }

    public void setScale(float s) {
        scale = s;
    }

    public void setVisible(boolean v) {
        visible = v;
        invalidate();
    }

    public boolean isVisible() {
        return visible;
    }

    /**
     * No-op: the focus ring no longer draws a keyline. Kept so callers that still
     * toggle it per-zone (HomeView) don't need to change.
     */
    public void setKeylineEnabled(boolean enabled) {
    }

    public void setMoveMode(boolean move) {
        moveMode = move;
    }

    /** Sets where the ring should glide to. Call every frame with the live target. */
    public void retarget(float tx, float ty, float tw, float th, float tr) {
        x.setTarget(tx);
        y.setTarget(ty);
        w.setTarget(tw);
        h.setTarget(th);
        radius.setTarget(tr);
    }

    public void snapToTarget() {
        x.snapTo(x.getTarget());
        y.snapTo(y.getTarget());
        w.snapTo(w.getTarget());
        h.snapTo(h.getTarget());
        radius.snapTo(radius.getTarget());
        invalidate();
    }

    public float liveCenterX() { return x.getValue() + w.getValue() / 2f; }
    public float liveCenterY() { return y.getValue() + h.getValue() / 2f; }

    @Override
    public boolean tick(float dt) {
        boolean moving = false;
        if (x.step(dt)) moving = true;
        if (y.step(dt)) moving = true;
        if (w.step(dt)) moving = true;
        if (h.step(dt)) moving = true;
        if (radius.step(dt)) moving = true;
        if (visible) invalidate();
        return moving;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!visible) return;
        canvas.save();
        canvas.scale(scale, scale);

        float rx = x.getValue();
        float ry = y.getValue();
        float rw = w.getValue();
        float rh = h.getValue();
        float rr = radius.getValue();
        ringRect.set(rx, ry, rx + rw, ry + rh);

        ringPaint.setColor(moveMode ? Metrics.COLOR_ACCENT_MOVE : Metrics.COLOR_TEXT);
        canvas.drawRoundRect(ringRect, rr, rr, ringPaint);

        if (moveMode) {
            drawChevrons(canvas, rx, ry, rw, rh);
        }

        canvas.restore();
    }

    private void drawChevrons(Canvas canvas, float rx, float ry, float rw, float rh) {
        float cx = rx + rw / 2f;
        float cy = ry + rh / 2f;
        float halfTileW = Metrics.TILE_W / 2f;
        float halfTileH = Metrics.TILE_H / 2f;
        float topY = cy - halfTileH - Metrics.MOVE_CHEVRON_V_OFFSET;
        float bottomY = cy + halfTileH + Metrics.MOVE_CHEVRON_V_OFFSET;
        float leftX = cx - halfTileW - Metrics.MOVE_CHEVRON_H_OFFSET;
        float rightX = cx + halfTileW + Metrics.MOVE_CHEVRON_H_OFFSET;
        float arm = 9f;

        chevronPath.reset();
        chevronPath.moveTo(cx - arm, topY + arm);
        chevronPath.lineTo(cx, topY - arm * 0.2f);
        chevronPath.lineTo(cx + arm, topY + arm);
        canvas.drawPath(chevronPath, chevronPaint);

        chevronPath.reset();
        chevronPath.moveTo(cx - arm, bottomY - arm);
        chevronPath.lineTo(cx, bottomY + arm * 0.2f);
        chevronPath.lineTo(cx + arm, bottomY - arm);
        canvas.drawPath(chevronPath, chevronPaint);

        chevronPath.reset();
        chevronPath.moveTo(leftX + arm, cy - arm);
        chevronPath.lineTo(leftX - arm * 0.2f, cy);
        chevronPath.lineTo(leftX + arm, cy + arm);
        canvas.drawPath(chevronPath, chevronPaint);

        chevronPath.reset();
        chevronPath.moveTo(rightX - arm, cy - arm);
        chevronPath.lineTo(rightX + arm * 0.2f, cy);
        chevronPath.lineTo(rightX - arm, cy + arm);
        canvas.drawPath(chevronPath, chevronPaint);
    }
}
