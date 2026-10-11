package com.halo.alicetvadblock;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

public class YouTubeRedirectService extends AccessibilityService {
    private static final int KEYCODE_VIDEO_APP_3 = 291;
    private static final String OFFICIAL_YOUTUBE = "com.google.android.youtube.tv";
    private static final String SMARTTUBE = "org.smarttube.stable";
    private long lastLaunchMs;

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KEYCODE_VIDEO_APP_3
                && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0
                && redirectEnabled()) {
            return launchSmartTube();
        }
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!redirectEnabled()) return;
        CharSequence p = event.getPackageName();
        if (p == null || !OFFICIAL_YOUTUBE.contentEquals(p)) return;

        long now = SystemClock.elapsedRealtime();
        if (now - lastLaunchMs < 2500) return;
        launchSmartTube();
    }

    private boolean redirectEnabled() {
        return getSharedPreferences("alice", MODE_PRIVATE)
                .getBoolean("youtube_redirect", true);
    }

    private boolean launchSmartTube() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(SMARTTUBE);
        if (launch == null) {
            Toast.makeText(this, "Chưa cài SmartTube - giữ YouTube stock", Toast.LENGTH_LONG).show();
            return false;
        }
        lastLaunchMs = SystemClock.elapsedRealtime();
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(launch);
        return true;
    }

    @Override
    public void onInterrupt() {
    }
}
