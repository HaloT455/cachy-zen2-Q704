package com.halo.youtube.bridge;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;

public class YouTubeBridgeService extends AccessibilityService {
    private static final String TAG = "YouTubeBridge";
    private static final String MOD_PACKAGE = "com.google.android.youtubx.tv";
    private static final String MOD_ACTIVITY = "com.google.android.apps.youtube.tv.activity.ShellActivity";
    private static final String OFFICIAL_PACKAGE = "com.google.android.youtube.tv";
    private static final String PLAY_STORE = "com.android.vending";
    private static final String KATNISS_PACKAGE = "com.google.android.katniss";
    private static final int KEYCODE_VIDEO_APP_3 = 291;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private long lastLaunchMs = 0L;
    private long lastOfficialRedirectMs = 0L;

    private String lastAssistantUtterance = "";
    private long lastAssistantUtteranceMs = 0L;

    private String lastAssistantClickedText = "";
    private long lastAssistantClickedTextMs = 0L;

    private final Runnable directAssistantLaunchRunnable = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.elapsedRealtime();
            String query = getRecentAssistantQuery(now);
            if (query.isEmpty()) {
                Log.i(TAG, "Direct Assistant launch -> mod home");
                launchModHome();
            } else {
                Log.i(TAG, "Direct Assistant launch query=" + query);
                launchModSearch(query);
            }
        }
    };

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KEYCODE_VIDEO_APP_3) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                launchModHome();
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

        if (KATNISS_PACKAGE.equals(pkg)) {
            captureAssistantContext(event);
            return;
        }

        // Google Assistant explicitly launches the Google-signed YouTube package.
        // Preserve the user's query by rebuilding a YouTube search deep-link for youtubx.
        if (OFFICIAL_PACKAGE.equals(pkg)) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastOfficialRedirectMs < 2500) return;
            lastOfficialRedirectMs = now;

            String query = getRecentAssistantQuery(now);
            if (query.isEmpty()) {
                launchModHome();
            } else {
                launchModSearch(query);
            }
            return;
        }

        // When Assistant routes to Play Store because official YouTube is absent,
        // immediately steal foreground back if this follows a recent YouTube voice
        // command. This avoids depending on Play Store's accessibility tree loading.
        if (PLAY_STORE.equals(pkg)) {
            long now = SystemClock.elapsedRealtime();
            String query = getRecentAssistantQuery(now);

            if (!query.isEmpty()) {
                Log.i(TAG, "Play Store intercepted, redirect query=" + query);
                launchModSearch(query);
                return;
            }

            // Fallback for cases where Katniss transcript arrives slightly later.
            handler.removeCallbacks(checkPlayStoreRunnable);
            handler.postDelayed(checkPlayStoreRunnable, 250);
            handler.postDelayed(checkPlayStoreRetryRunnable, 650);
            handler.postDelayed(checkPlayStoreRetry2Runnable, 1200);
        }
    }

    private void captureAssistantContext(AccessibilityEvent event) {
        long now = SystemClock.elapsedRealtime();

        // A clicked Assistant result is more specific than the spoken command.
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            String clicked = bestTextFromEvent(event, false);
            if (isUsefulClickedText(clicked)) {
                lastAssistantClickedText = clicked.trim();
                lastAssistantClickedTextMs = now;
            }
        }

        String spoken = bestTextFromEvent(event, true);
        if (!spoken.isEmpty() && spoken.toLowerCase(Locale.ROOT).contains("youtube")) {
            lastAssistantUtterance = spoken.trim();
            lastAssistantUtteranceMs = now;

            // Direct fallback for TVs where Assistant reports "no supporting app"
            // when the Google-signed YouTube package is absent for user 0.
            // Debounce transcription updates; the last (final/longest) phrase wins.
            Log.i(TAG, "Katniss utterance=" + lastAssistantUtterance);
            handler.removeCallbacks(directAssistantLaunchRunnable);
            handler.postDelayed(directAssistantLaunchRunnable, 250);
        }
    }

    private String bestTextFromEvent(AccessibilityEvent event, boolean requireYoutube) {
        String best = "";

        List<CharSequence> texts = event.getText();
        if (texts != null) {
            for (CharSequence cs : texts) {
                best = chooseBetter(best, cs, requireYoutube);
            }
        }

        best = chooseBetter(best, event.getContentDescription(), requireYoutube);

        AccessibilityNodeInfo source = event.getSource();
        if (source != null) {
            best = chooseBetter(best, source.getText(), requireYoutube);
            best = chooseBetter(best, source.getContentDescription(), requireYoutube);
        }

        // Katniss often updates a custom transcription view via content-change events.
        // Scan a small portion of the active tree to catch the final recognized phrase.
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
            q.add(root);
            int inspected = 0;
            while (!q.isEmpty() && inspected < 180) {
                AccessibilityNodeInfo n = q.removeFirst();
                inspected++;

                best = chooseBetter(best, n.getText(), requireYoutube);
                best = chooseBetter(best, n.getContentDescription(), requireYoutube);

                for (int i = 0; i < n.getChildCount(); i++) {
                    AccessibilityNodeInfo child = n.getChild(i);
                    if (child != null) q.addLast(child);
                }
            }
        }

        return best;
    }

    private String chooseBetter(String current, CharSequence candidateSeq, boolean requireYoutube) {
        if (candidateSeq == null) return current;

        String candidate = candidateSeq.toString().trim();
        if (candidate.length() < 2 || candidate.length() > 220) return current;

        String lower = candidate.toLowerCase(Locale.ROOT);
        if (requireYoutube && !lower.contains("youtube")) return current;

        // Prefer the longest human-readable phrase; final transcription is normally
        // longer than intermediate fragments such as "mở" or "mở karaoke".
        if (candidate.length() > current.length()) return candidate;
        return current;
    }

    private boolean isUsefulClickedText(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.length() < 2 || t.length() > 180) return false;

        String lower = t.toLowerCase(Locale.ROOT);
        return !(lower.equals("youtube")
                || lower.equals("mở")
                || lower.equals("phát")
                || lower.equals("xem thêm")
                || lower.equals("more"));
    }

    private String getRecentAssistantQuery(long now) {
        // Prefer the user's spoken command. Assistant result cards can contain
        // recommendation/title text unrelated to the exact requested query.
        if (!lastAssistantUtterance.isEmpty() && now - lastAssistantUtteranceMs <= 9000) {
            String q = sanitizeQuery(lastAssistantUtterance);
            if (!q.isEmpty()) return q;
        }

        // Fallback: use a clicked Assistant result only when no recent spoken
        // YouTube command was captured.
        if (!lastAssistantClickedText.isEmpty() && now - lastAssistantClickedTextMs <= 6000) {
            String q = sanitizeQuery(lastAssistantClickedText);
            if (!q.isEmpty()) return q;
        }

        return "";
    }

    private String sanitizeQuery(String raw) {
        if (raw == null) return "";
        String q = raw.trim();

        // Remove common Vietnamese command wrappers while keeping the actual title/query.
        q = q.replaceFirst("(?iu)^\\s*(hãy\\s+|giúp\\s+)?(mở|phát|tìm|tìm kiếm|xem|cho (tôi|mình) (xem|nghe))\\s+", "");
        q = q.replaceFirst("(?iu)\\s+(trên|ở|bằng)\\s+youtube(?:\\s+(đi|nha|nhé|giúp.*))?\\s*$", "");
        q = q.replaceFirst("(?iu)\\s+youtube(?:\\s+(đi|nha|nhé|giúp.*))?\\s*$", "");
        q = q.replaceFirst("(?iu)^youtube\\s*", "");
        q = q.replaceAll("(?iu)\\s+", " ").trim();

        if (q.equalsIgnoreCase("youtube")) return "";
        return q;
    }

    private void launchModSearch(String query) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastLaunchMs < 700) return;
        lastLaunchMs = now;

        Uri uri = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query));
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        intent.setComponent(new ComponentName(MOD_PACKAGE, MOD_ACTIVITY));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        try {
            startActivity(intent);
        } catch (Throwable ignored) {
            launchModHomeFallback();
        }
    }

    private void launchModHome() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastLaunchMs < 700) return;
        lastLaunchMs = now;
        launchModHomeFallback();
    }

    private void launchModHomeFallback() {
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

    private final Runnable checkPlayStoreRunnable = new Runnable() {
        @Override
        public void run() {
            redirectFromPlayStoreIfNeeded();
        }
    };

    private final Runnable checkPlayStoreRetryRunnable = new Runnable() {
        @Override
        public void run() {
            redirectFromPlayStoreIfNeeded();
        }
    };

    private final Runnable checkPlayStoreRetry2Runnable = new Runnable() {
        @Override
        public void run() {
            redirectFromPlayStoreIfNeeded();
        }
    };

    private void redirectFromPlayStoreIfNeeded() {
        long now = SystemClock.elapsedRealtime();
        String query = getRecentAssistantQuery(now);
        if (!query.isEmpty()) {
            Log.i(TAG, "Delayed Play Store redirect query=" + query);
            launchModSearch(query);
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && treeContainsYouTube(root)) {
            Log.i(TAG, "Play Store YouTube page detected -> mod home");
            launchModHome();
        }
    }

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

    @Override
    public void onInterrupt() {
    }
}
