package com.lighthome.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class PackageChangeReceiver extends BroadcastReceiver {

    public interface Listener {
        void onPackagesChanged();
    }

    private final Listener listener;

    public PackageChangeReceiver(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (listener != null) {
            listener.onPackagesChanged();
        }
    }
}
