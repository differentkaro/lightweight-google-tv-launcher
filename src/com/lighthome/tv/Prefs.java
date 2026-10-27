package com.lighthome.tv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** All persisted launcher state. Writes only happen when something actually changed. */
public final class Prefs {

    private static final String FILE = "lighthome_prefs";
    private static final String KEY_ORDER = "app_order";
    private static final String KEY_HIDDEN = "hidden_packages";
    private static final String KEY_LAST_ZONE = "last_zone";
    private static final String KEY_LAST_GRID_ROW = "last_grid_row";
    private static final String KEY_LAST_GRID_COL = "last_grid_col";
    private static final String KEY_LAST_HERO_INDEX = "last_hero_index";

    private final SharedPreferences prefs;

    public Prefs(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public List<String> getOrder() {
        String saved = prefs.getString(KEY_ORDER, "");
        List<String> list = new ArrayList<>();
        if (saved != null && !saved.isEmpty()) {
            for (String pkg : saved.split(",")) {
                if (!pkg.isEmpty()) list.add(pkg);
            }
        }
        return list;
    }

    public void setOrder(List<String> packages) {
        StringBuilder sb = new StringBuilder();
        for (String pkg : packages) {
            if (sb.length() > 0) sb.append(',');
            sb.append(pkg);
        }
        String newValue = sb.toString();
        if (!newValue.equals(prefs.getString(KEY_ORDER, ""))) {
            prefs.edit().putString(KEY_ORDER, newValue).apply();
        }
    }

    public Set<String> getHidden() {
        Set<String> saved = prefs.getStringSet(KEY_HIDDEN, null);
        return saved != null ? new HashSet<>(saved) : new HashSet<String>();
    }

    public void setHidden(Set<String> hidden) {
        prefs.edit().putStringSet(KEY_HIDDEN, hidden).apply();
    }

    public void setHiddenApp(String pkg, boolean hidden) {
        Set<String> current = getHidden();
        boolean changed = hidden ? current.add(pkg) : current.remove(pkg);
        if (changed) setHidden(current);
    }

    // Remembered focus, restored across onPause/onResume (never reset on its own).
    public int getLastZone(int defaultValue) {
        return prefs.getInt(KEY_LAST_ZONE, defaultValue);
    }

    public int getLastGridRow() {
        return prefs.getInt(KEY_LAST_GRID_ROW, 0);
    }

    public int getLastGridCol() {
        return prefs.getInt(KEY_LAST_GRID_COL, 0);
    }

    public int getLastHeroIndex() {
        return prefs.getInt(KEY_LAST_HERO_INDEX, 0);
    }

    public void saveFocus(int zone, int gridRow, int gridCol, int heroIndex) {
        prefs.edit()
                .putInt(KEY_LAST_ZONE, zone)
                .putInt(KEY_LAST_GRID_ROW, gridRow)
                .putInt(KEY_LAST_GRID_COL, gridCol)
                .putInt(KEY_LAST_HERO_INDEX, heroIndex)
                .apply();
    }
}
