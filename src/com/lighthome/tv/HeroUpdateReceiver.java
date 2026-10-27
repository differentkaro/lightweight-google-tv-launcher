package com.lighthome.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

/** Dynamically registered only while the activity is resumed; hears HeroSyncService's broadcast. */
public final class HeroUpdateReceiver extends BroadcastReceiver {

    public interface Listener {
        void onHeroUpdated();
    }

    private final Listener listener;

    public HeroUpdateReceiver(Listener listener) {
        this.listener = listener;
    }

    public static IntentFilter filter() {
        return new IntentFilter(HeroRepository.ACTION_HERO_UPDATED);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (listener != null) listener.onHeroUpdated();
    }
}
