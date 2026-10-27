package com.lighthome.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.ViewGroup;

/**
 * Thin shell: all UI, state and motion live in HomeView. This class only wires up
 * Android plumbing (lifecycle, broadcasts, key dispatch) and forwards to it.
 */
public final class MainActivity extends Activity {

    private HomeView homeView;
    private PackageChangeReceiver packageReceiver;
    private HeroUpdateReceiver heroReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        IconCache.preload(this);
        homeView = new HomeView(this);
        setContentView(homeView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        RemoteKeyServiceGuard.ensureEnabled(getApplicationContext());
        HeroSyncService.schedulePeriodic(getApplicationContext());
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerPackageReceiver();
        registerHeroReceiver();
        homeView.onResume();
        RemoteKeyServiceGuard.ensureEnabled(getApplicationContext());
    }

    @Override
    protected void onPause() {
        super.onPause();
        homeView.onPause();
        unregisterSafely(packageReceiver);
        unregisterSafely(heroReceiver);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        homeView.onNewIntentHome();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        homeView.onTrimMemory(level);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (homeView.onKeyEvent(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        // Intentionally no-op: HomeView.onKeyEvent already consumes BACK everywhere
        // (it returns to HERO or does nothing there). The launcher must never finish.
    }

    private void registerPackageReceiver() {
        packageReceiver = new PackageChangeReceiver(() -> homeView.onPackagesChanged());
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        registerReceiver(packageReceiver, filter);
    }

    private void registerHeroReceiver() {
        heroReceiver = new HeroUpdateReceiver(() -> homeView.onHeroUpdatedBroadcast());
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(heroReceiver, HeroUpdateReceiver.filter(), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(heroReceiver, HeroUpdateReceiver.filter());
        }
    }

    private void unregisterSafely(android.content.BroadcastReceiver receiver) {
        if (receiver == null) return;
        try {
            unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
    }
}
