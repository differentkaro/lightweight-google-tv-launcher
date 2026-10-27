package com.lighthome.tv;

/**
 * A single critically-damped spring value. Retargetable (call {@link #setTarget}
 * at any time; velocity carries over, so re-aiming never causes a visible pop).
 * Because this is driven by real elapsed time (not the system's animator duration
 * scale), it keeps a consistent feel regardless of Settings > Developer options.
 *
 * Critical damping: damping = 2 * sqrt(stiffness) for unit mass, which settles in
 * roughly 4-5 / sqrt(stiffness) seconds without overshoot.
 */
public final class Spring {

    private static final float REST_VELOCITY_EPS = 0.01f;
    private static final float REST_DELTA_EPS = 0.05f;

    private float value;
    private float velocity;
    private float target;
    private float stiffness;
    private float damping;

    public Spring(float initial, float stiffness) {
        this.value = initial;
        this.target = initial;
        setStiffness(stiffness);
    }

    public void setTarget(float t) {
        this.target = t;
    }

    /**
     * Changes how stiff/fast this spring is, keeping critical damping. Lets one Spring
     * object serve several motions at different speeds (e.g. a tile's focus-scale spring
     * is snappier for a press-down than for settling into the focused scale) without
     * allocating a new Spring -- current value/velocity/target carry over untouched.
     */
    public void setStiffness(float stiffness) {
        this.stiffness = stiffness;
        this.damping = 2f * (float) Math.sqrt(stiffness);
    }

    public void snapTo(float v) {
        this.value = v;
        this.target = v;
        this.velocity = 0f;
    }

    public float getValue() {
        return value;
    }

    public float getTarget() {
        return target;
    }

    public boolean isSettled() {
        return Math.abs(velocity) < REST_VELOCITY_EPS && Math.abs(target - value) < REST_DELTA_EPS;
    }

    /** Advances the simulation by dt seconds. Returns true if the value is still moving. */
    public boolean step(float dt) {
        if (isSettled()) {
            return false;
        }
        float displacement = value - target;
        float springForce = -stiffness * displacement;
        float dampingForce = -damping * velocity;
        float accel = springForce + dampingForce;
        velocity += accel * dt;
        value += velocity * dt;
        if (isSettled()) {
            value = target;
            velocity = 0f;
            return false;
        }
        return true;
    }
}
