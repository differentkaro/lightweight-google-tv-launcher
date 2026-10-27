package com.lighthome.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Runs RemoteKeyServiceGuard after every boot, since that's the main point
 * where TCL/Android has been observed to drop accessibility services.
 */
public final class BootCompletedReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            RemoteKeyServiceGuard.ensureEnabled(context.getApplicationContext());
            HeroSyncService.schedulePeriodic(context.getApplicationContext());
        }
    }
}
