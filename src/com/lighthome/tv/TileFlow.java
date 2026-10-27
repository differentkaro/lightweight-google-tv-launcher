package com.lighthome.tv;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One position spring pair per visible app (keyed by package name), so that when the
 * grid is reordered (move mode, or an app add/remove), tiles whose slot changed slide
 * smoothly to their new spot instead of teleporting. Idle cost is ~0: settled springs
 * report "not moving" and the ticker simply does nothing.
 */
public final class TileFlow implements MotionClock.Ticker {

    private final Map<String, Spring> xs = new HashMap<>();
    private final Map<String, Spring> ys = new HashMap<>();
    private final List<String> order = new ArrayList<>();

    /** Re-syncs slot targets from the current order. animate=false snaps instantly (e.g. first load). */
    public void sync(List<AppInfo> apps, boolean animate) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        order.clear();
        for (int i = 0; i < apps.size(); i++) {
            String pkg = apps.get(i).packageName;
            order.add(pkg);
            float tx = Metrics.tileLeft(i % Metrics.COLS);
            float ty = Metrics.tileTop(i / Metrics.COLS);
            Spring sx = xs.get(pkg);
            Spring sy = ys.get(pkg);
            if (sx == null) {
                sx = new Spring(tx, Motion.MOVE_NEIGHBOR_STIFFNESS);
                sy = new Spring(ty, Motion.MOVE_NEIGHBOR_STIFFNESS);
                xs.put(pkg, sx);
                ys.put(pkg, sy);
            }
            if (animate) {
                sx.setTarget(tx);
                sy.setTarget(ty);
            } else {
                sx.snapTo(tx);
                sy.snapTo(ty);
            }
            seen.add(pkg);
        }
        xs.keySet().retainAll(seen);
        ys.keySet().retainAll(seen);
    }

    public float x(String pkg, float fallback) {
        Spring s = xs.get(pkg);
        return s != null ? s.getValue() : fallback;
    }

    public float y(String pkg, float fallback) {
        Spring s = ys.get(pkg);
        return s != null ? s.getValue() : fallback;
    }

    /** Hard-repositions one app (e.g. the tile being dragged in move mode, driven elsewhere). */
    public void freeze(String pkg) {
        Spring sx = xs.get(pkg);
        Spring sy = ys.get(pkg);
        if (sx != null) sx.setTarget(sx.getValue());
        if (sy != null) sy.setTarget(sy.getValue());
    }

    @Override
    public boolean tick(float dt) {
        boolean moving = false;
        for (Spring s : xs.values()) if (s.step(dt)) moving = true;
        for (Spring s : ys.values()) if (s.step(dt)) moving = true;
        return moving;
    }
}
