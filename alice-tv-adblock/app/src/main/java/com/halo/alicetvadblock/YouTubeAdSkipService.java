package com.halo.alicetvadblock;

import android.accessibilityservice.AccessibilityService;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.Locale;

public class YouTubeAdSkipService extends AccessibilityService {
    private static final String TAG = "AliceYTAdSkip";
    private static final String YOUTUBE = "com.google.android.youtube.tv";
    private long lastClickMs = 0L;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!getSharedPreferences("alice", MODE_PRIVATE)
                .getBoolean("youtube_adskip", true)) return;

        CharSequence pkg = event.getPackageName();
        if (pkg == null || !YOUTUBE.contentEquals(pkg)) return;

        long now = SystemClock.elapsedRealtime();
        if (now - lastClickMs < 800) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        AccessibilityNodeInfo skip = findSkipNode(root);
        if (skip != null) {
            AccessibilityNodeInfo clickable = findClickable(skip);
            if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                lastClickMs = now;
                Log.i(TAG, "Clicked YouTube Skip Ad");
            }
        }
    }

    private AccessibilityNodeInfo findSkipNode(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int inspected = 0;

        while (!q.isEmpty() && inspected < 500) {
            AccessibilityNodeInfo n = q.removeFirst();
            inspected++;

            String text = merge(n.getText(), n.getContentDescription());
            if (isSkipLabel(text)) return n;

            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.addLast(c);
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findClickable(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; cur != null && i < 5; i++) {
            if (cur.isClickable()) return cur;
            cur = cur.getParent();
        }
        return n;
    }

    private boolean isSkipLabel(String raw) {
        if (raw == null) return false;
        String s = raw.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();

        return s.equals("skip ad")
                || s.equals("skip ads")
                || s.startsWith("skip ad ")
                || s.startsWith("skip ads ")
                || s.equals("bỏ qua quảng cáo")
                || s.startsWith("bỏ qua quảng cáo ")
                || s.equals("bỏ qua")
                || s.startsWith("bỏ qua ");
    }

    private String merge(CharSequence a, CharSequence b) {
        String sa = a == null ? "" : a.toString();
        String sb = b == null ? "" : b.toString();
        return (sa + " " + sb).trim();
    }

    @Override
    public void onInterrupt() {
    }
}
