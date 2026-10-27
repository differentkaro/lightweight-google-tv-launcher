package com.lighthome.tv;

/**
 * Every motion constant in one place. All springs are critically damped (Spring.java
 * derives damping = 2*sqrt(stiffness) automatically), so none of these can overshoot or
 * bounce -- raising a stiffness value only makes that motion faster, never bouncier.
 * Approximate settle time for a critically-damped spring is ~4.5/sqrt(stiffness) sec.
 *
 * Tune feel by editing this file only; nothing else should hardcode a duration or
 * stiffness number.
 */
public final class Motion {

    private Motion() {}

    /** Focus ring glide between targets. Settles in ~150-170ms. Also used for: */
    public static final float RING_STIFFNESS = 750f;
    /** Focused-tile / focused-pill scale pop (rest <-> focused) -- same feel as the ring. */
    public static final float FOCUS_SCALE_STIFFNESS = RING_STIFFNESS;
    /** Move-mode: a displaced neighbour tile sliding to its new slot -- same feel as the ring. */
    public static final float MOVE_NEIGHBOR_STIFFNESS = RING_STIFFNESS;

    /** Page scroll: hero<->grid shift and in-grid row scrolling. Settles in ~230-250ms. */
    public static final float PAGE_STIFFNESS = 500f;
    // Backdrop dim and hero-text fade are DERIVED from the page spring's own progress
    // (see HomeView.tick()), not separate springs, so they always move at this same speed.

    /** Drawer / popover menu / sheet open+close. Settles in ~180ms. */
    public static final float SHEET_STIFFNESS = 600f;
    /** Hide/undo toast fade -- grouped with the other overlay chrome. */
    public static final float TOAST_STIFFNESS = SHEET_STIFFNESS;

    /** Hero backdrop crossfade: time-based (not a spring), cubic easing. */
    public static final long HERO_CROSSFADE_MS = 600L;

    /** App-name label fade under the focused tile: time-based, no delay. */
    public static final float LABEL_FADE_STIFFNESS = 2000f; // ~100ms

    /** Press feedback: quick dip on down, slightly slower recovery on up. */
    public static final float PRESS_DOWN_STIFFNESS = 5600f; // ~60ms
    public static final float PRESS_UP_STIFFNESS = 2000f;   // ~100ms
    public static final float PRESS_SCALE = 0.96f;

    /** Long-press-to-open-menu threshold. Deliberately a hair under the platform's
     *  ~500ms long-press timeout so it reliably recognizes a genuinely held key. */
    public static final long LONG_PRESS_MS = 450L;

    /** OK-on-a-tile: how long we wait before showing ANY feedback. Under this, a quick
     *  tap just launches with no visible press state; at/after it (but before
     *  LONG_PRESS_MS) we know the key is genuinely being held, so we switch to the
     *  subtle hold-dim state (never the press-shrink -- that used to fire immediately
     *  on key-down and looked like a launch animation right before the hold menu
     *  popped open). */
    public static final long HOLD_INDICATOR_MS = 150L;
}
