package com.lighthome.tv;

import android.view.Choreographer;

/**
 * The ONE Choreographer.FrameCallback for the whole UI. It runs only while at least
 * one registered {@link Ticker} reports it is still unsettled, so an idle home screen
 * draws zero frames. Callers register once; this class handles re-posting itself.
 */
public final class MotionClock implements Choreographer.FrameCallback {

    /** Something that can advance by dt seconds and report whether it's still moving. */
    public interface Ticker {
        /** Return true if this ticker changed anything and a redraw is needed. */
        boolean tick(float dtSeconds);
    }

    private static final float MAX_DT = 1f / 30f;

    private final Choreographer choreographer;
    private final Ticker[] tickers;
    private final Runnable onFrame;
    private long lastFrameTimeNanos = 0L;
    private boolean running = false;

    public MotionClock(Ticker[] tickers, Runnable onFrame) {
        this.choreographer = Choreographer.getInstance();
        this.tickers = tickers;
        this.onFrame = onFrame;
    }

    /** Call whenever something may have become unsettled (e.g. a spring retargeted). */
    public void requestStart() {
        if (!running) {
            running = true;
            lastFrameTimeNanos = 0L;
            choreographer.postFrameCallback(this);
        }
    }

    public void stop() {
        running = false;
        choreographer.removeFrameCallback(this);
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!running) return;
        if (lastFrameTimeNanos == 0L) {
            lastFrameTimeNanos = frameTimeNanos;
        }
        float dt = (frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000f;
        lastFrameTimeNanos = frameTimeNanos;
        if (dt > MAX_DT) dt = MAX_DT;
        if (dt <= 0f) dt = 1f / 60f;

        boolean anyMoving = false;
        for (int i = 0; i < tickers.length; i++) {
            if (tickers[i].tick(dt)) anyMoving = true;
        }
        onFrame.run();

        if (anyMoving) {
            choreographer.postFrameCallback(this);
        } else {
            running = false;
        }
    }
}
