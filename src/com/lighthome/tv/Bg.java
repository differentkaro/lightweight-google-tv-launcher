package com.lighthome.tv;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ONE shared background thread for the UI process: app-list scanning/decoding,
 * hero bitmap decoding, and glass-blur recomputation all funnel through here so the
 * process never spins up more than a single extra thread. The UI process never
 * opens a network socket; that only happens in the ":sync" process (HeroSyncService).
 */
public final class Bg {

    private Bg() {}

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lighthome-bg");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    public static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static void run(Runnable r) {
        EXECUTOR.execute(r);
    }
}
