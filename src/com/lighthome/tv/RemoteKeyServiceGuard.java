package com.lighthome.tv;

import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

/**
 * TCL/Android sometimes drops accessibility services from
 * Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES across OTA updates or reboots.
 * This re-adds RemoteKeyService's component to that setting (preserving any
 * other services already listed) and makes sure accessibility is on overall.
 *
 * Requires android.permission.WRITE_SECURE_SETTINGS, which is granted out of
 * band via adb (it cannot be requested at runtime). Every call is wrapped so a
 * SecurityException (permission not granted yet) never crashes the caller.
 */
public final class RemoteKeyServiceGuard {

    private static final String TAG = "RemoteKeyServiceGuard";
    private static final String COMPONENT = "com.lighthome.tv/com.lighthome.tv.RemoteKeyService";

    // Set once this process has already cycled the setting -- see the comment in
    // ensureEnabled() below for why that cycle only needs to happen once per process.
    private static volatile boolean cycledThisProcess = false;

    public static void ensureEnabled(final Context context) {
        // No remote_keys configured -> stay fully dormant. Never touch
        // Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES on a build where the
        // feature isn't configured at all; this is what keeps the accessibility
        // service genuinely opt-in (see RemoteKeys).
        if (!RemoteKeys.hasAnyMapping()) {
            return;
        }
        if (!cycledThisProcess) {
            cycledThisProcess = true;
            // AccessibilityManagerService can leave RemoteKeyService marked "crashed"
            // (dumpsys accessibility) after `am force-stop` kills every process of this
            // app out from under its live accessibility binding -- observed after a
            // routine force-stop-then-relaunch cycle, not just an OTA/reboot drop. A
            // component that's merely MISSING from the setting gets re-added below and
            // that alone is enough to trigger a fresh bind, but a component that's
            // already PRESENT-but-crashed needs the setting to actually CHANGE value to
            // make AccessibilityManagerService re-read it and retry the bind. Writing
            // "remove" immediately followed by "re-add" from the same thread isn't
            // enough either -- verified on-device the two writes land before AMS's
            // ContentObserver wakes up even once, so it only ever observes the final
            // value, which is identical to what it already had cached, and never
            // rebinds. A real (if brief) delay between the two writes is required, so
            // this runs off the main thread on Bg's shared background thread.
            final Context appContext = context.getApplicationContext();
            Bg.run(new Runnable() {
                @Override public void run() { cycleComponent(appContext); }
            });
            return;
        }

        try {
            String current = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);

            if (isComponentPresent(current, COMPONENT)) {
                ensureAccessibilityOn(context);
                return;
            }

            String updated;
            if (TextUtils.isEmpty(current)) {
                updated = COMPONENT;
            } else {
                updated = current + ":" + COMPONENT;
            }

            Settings.Secure.putString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    updated);

            ensureAccessibilityOn(context);
            Log.i(TAG, "Re-added RemoteKeyService to enabled_accessibility_services");
        } catch (SecurityException e) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS not granted; cannot self-heal accessibility state", e);
        } catch (Exception e) {
            Log.w(TAG, "Failed to ensure RemoteKeyService is enabled", e);
        }
    }

    /**
     * Runs once per process start, off the main thread -- see ensureEnabled() above.
     * Verified on-device that writing an EMPTY string (putString(key, "")) is not
     * enough to clear a "crashed" flag on an already-listed component -- Settings
     * still has a row, so from AccessibilityManagerService's side nothing about the
     * row's existence changed. An actual ContentResolver.delete() of the row (what the
     * `settings delete` shell command does) is what reliably clears it; putString()
     * with the final value then recreates the row with our component enabled.
     */
    private static void cycleComponent(Context context) {
        try {
            String current = Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            context.getContentResolver().delete(
                    Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), null, null);
            try { Thread.sleep(400); } catch (InterruptedException ignored) { }

            String withoutComponent = removeComponent(current, COMPONENT);
            String updated = TextUtils.isEmpty(withoutComponent) ? COMPONENT : withoutComponent + ":" + COMPONENT;
            Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated);
            ensureAccessibilityOn(context);
            Log.i(TAG, "Cycled RemoteKeyService in enabled_accessibility_services (clears any stale crashed state)");
        } catch (SecurityException e) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS not granted; cannot self-heal accessibility state", e);
        } catch (Exception e) {
            Log.w(TAG, "Failed to cycle RemoteKeyService accessibility state", e);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String removeComponent(String enabledServices, String component) {
        if (TextUtils.isEmpty(enabledServices)) return "";
        StringBuilder sb = new StringBuilder();
        for (String part : enabledServices.split(":")) {
            if (part.isEmpty() || part.equals(component)) continue;
            if (sb.length() > 0) sb.append(":");
            sb.append(part);
        }
        return sb.toString();
    }

    private static void ensureAccessibilityOn(Context context) {
        try {
            int enabled = Settings.Secure.getInt(
                    context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            if (enabled != 1) {
                Settings.Secure.putInt(
                        context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS not granted; cannot set accessibility_enabled", e);
        }
    }

    private static boolean isComponentPresent(String enabledServices, String component) {
        if (TextUtils.isEmpty(enabledServices)) return false;
        for (String part : enabledServices.split(":")) {
            if (part.equals(component)) return true;
        }
        return false;
    }

    private RemoteKeyServiceGuard() {}
}
