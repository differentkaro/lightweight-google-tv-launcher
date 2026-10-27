package com.lighthome.tv;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

/**
 * Intercepts a handful of TCL remote buttons (Guard, Media, TCL Channel) before
 * TclPhoneWindowManager's interceptKeyBeforeDispatching gets to act on them, and
 * launches a chosen app instead. Declares canRequestFilterKeyEvents so
 * AccessibilityInputFilter hands us key events ahead of normal dispatch.
 *
 * Every key not in RemoteKeys' map is returned as unhandled (false) immediately,
 * so this service never interferes with normal remote/DPAD behavior.
 *
 * NOTE (see docs/TCL-NOTES.md for the full writeup): on this TCL/Android 11
 * build, onKeyEvent was verified to never fire for ANY key (not just the TCL
 * custom codes, also plain DPAD_RIGHT), while onAccessibilityEvent fires
 * normally for the same service instance. The implementation below matches
 * the spec exactly; the gap is in the platform's key-event-filter routing,
 * not in this code.
 */
public final class RemoteKeyService extends AccessibilityService {

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (!RemoteKeys.isMapped(keyCode)) {
            return false;
        }

        int action = event.getAction();
        if (action == KeyEvent.ACTION_DOWN) {
            // Consume DOWN too, so the system/TCL handler never sees either half
            // of the key press.
            return true;
        }
        if (action == KeyEvent.ACTION_UP) {
            if (event.getRepeatCount() == 0) {
                launch(keyCode);
            }
            return true;
        }
        return false;
    }

    private void launch(int keyCode) {
        String packageName = RemoteKeys.packageFor(keyCode);
        String label = RemoteKeys.labelFor(keyCode);
        PackageManager pm = getPackageManager();

        Intent launchIntent = pm.getLeanbackLaunchIntentForPackage(packageName);
        if (launchIntent == null) {
            launchIntent = pm.getLaunchIntentForPackage(packageName);
        }

        if (launchIntent == null) {
            Toast.makeText(this, label + " isn't installed", Toast.LENGTH_SHORT).show();
            openPlayStorePage(packageName);
            return;
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(launchIntent);
        } catch (Exception e) {
            Toast.makeText(this, label + " isn't installed", Toast.LENGTH_SHORT).show();
            openPlayStorePage(packageName);
        }
    }

    private void openPlayStorePage(String packageName) {
        try {
            Intent storeIntent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + Uri.encode(packageName)));
            storeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(storeIntent);
        } catch (Exception ignored) {
            try {
                Intent webIntent = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=" + Uri.encode(packageName)));
                webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(webIntent);
            } catch (Exception ignored2) {
                // No Play Store available; nothing more we can do.
            }
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No-op: we only care about key events.
    }

    @Override
    public void onInterrupt() {
        // No-op.
    }
}
