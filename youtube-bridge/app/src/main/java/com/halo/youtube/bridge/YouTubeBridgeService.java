package com.halo.youtube.bridge;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.Locale;

public class YouTubeBridgeService extends AccessibilityService {
    private static final String MOD_PACKAGE = "com.google.android.youtubx.tv";
    private static final String MOD_ACTIVITY = "com.google.android.apps.youtube.tv.activity.ShellActivity";
    private static final String OFFICIAL_PACKAGE = "com.google.android.youtube.tv";
    private static final String PLAY_STORE = "com.android.vending";
    private static final int KEYCODE_VIDEO_APP_3 = 291;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastLaunchMs = 0L;

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KEYCODE_VIDEO_APP_3) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                launchMod();
            }
            return true;
        }
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence pkgSeq = event.getPackageName();
        if (pkgSeq == null) return;

        String pkg = pkgSeq.toString();

        // Assistant / system / remote launched the official YouTube package.
        if (OFFICIAL_PACKAGE.equals(pkg)) {
            launchMod();
            return;
        }

        // If Assistant says YouTube is missing and opens its Play Store page,
        // detect YouTube in the visible page and redirect to the patched app.
        if (PLAY_STORE.equals(pkg)) {
            handler.removeCallbacks(checkPlayStoreRunnable);
            handler.postDelayed(checkPlayStoreRunnable, 250);
        }
    }

    private final Runnable checkPlayStoreRunnable = new Runnable() {
        @Override
        public void run() {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && treeContainsYouTube(root)) {
                launchMod();
            }
        }
    };

    private boolean treeContainsYouTube(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);

        int inspected = 0;
        while (!q.isEmpty() && inspected < 300) {
            AccessibilityNodeInfo n = q.removeFirst();
            inspected++;

            if (containsYouTube(n.getText()) || containsYouTube(n.getContentDescription())) {
                return true;
            }

            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo child = n.getChild(i);
                if (child != null) q.addLast(child);
            }
        }
        return false;
    }

    private boolean containsYouTube(CharSequence value) {
        if (value == null) return false;
        return value.toString().toLowerCase(Locale.ROOT).contains("youtube");
    }

    private void launchMod() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastLaunchMs < 700) return;
        lastLaunchMs = now;

        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        intent.setComponent(new ComponentName(MOD_PACKAGE, MOD_ACTIVITY));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        try {
            startActivity(intent);
        } catch (Throwable ignored) {
            Intent fallback = getPackageManager().getLaunchIntentForPackage(MOD_PACKAGE);
            if (fallback != null) {
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(fallback);
            }
        }
    }

    @Override
    public void onInterrupt() {
    }
}
