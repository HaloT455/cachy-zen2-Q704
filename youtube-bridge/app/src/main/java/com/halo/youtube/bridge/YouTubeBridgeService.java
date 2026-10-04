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
    private static final String LAUNCHER_PACKAGE = "com.google.android.apps.tv.launcherx";
    private static final String FRAMEWORK_STUB_PACKAGE = "com.android.tv.frameworkpackagestubs";
    private static final int KEYCODE_VIDEO_APP_3 = 291;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private long lastLaunchMs = 0L;
    private long lastOfficialRedirectMs = 0L;
    private long lastAssistantRedirectMs = 0L;

    private String lastAssistantUtterance = "";
    private long lastAssistantUtteranceMs = 0L;

    // Last human voice query even when it does not explicitly mention YouTube.
    // Used only after the user chooses a YouTube/video result; it never auto-launches.
    private String lastAssistantAnyUtterance = "";
    private long lastAssistantAnyUtteranceMs = 0L;

    private String lastAssistantClickedText = "";
    private long lastAssistantClickedTextMs = 0L;

    private final Runnable directAssistantLaunchRunnable = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.elapsedRealtime();
            String query = getRecentAssistantQuery(now);
            lastAssistantRedirectMs = now;

            // Dismiss the Assistant surface immediately after the final YouTube
            // transcript is captured. On BRAVIA this prevents Katniss from continuing
            // into the fallback response ("no app supports this package") while we
            // route the request to youtubx ourselves.
            Log.i(TAG, "Dismissing Assistant before direct YouTube redirect");
            performGlobalAction(GLOBAL_ACTION_BACK);

            handler.removeCallbacks(checkPlayStoreRunnable);
            handler.removeCallbacks(checkPlayStoreRetryRunnable);
            handler.removeCallbacks(checkPlayStoreRetry2Runnable);

            if (query.isEmpty()) {
                Log.i(TAG, "Direct Assistant launch -> mod home");
                handler.postDelayed(this::launchModHomeAfterDismiss, 90);
            } else {
                Log.i(TAG, "Direct Assistant launch query=" + query);
                final String q = query;
                handler.postDelayed(() -> launchModSearch(q), 90);
            }

            handler.postDelayed(YouTubeBridgeService.this::bringModTaskToFront, 650);
        }

        private void launchModHomeAfterDismiss() {
            YouTubeBridgeService.this.launchModHome();
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

            // If Katniss emits another visible response event immediately after we
            // already redirected a YouTube request, dismiss that response surface too.
            long now = SystemClock.elapsedRealtime();
            if (lastAssistantRedirectMs > 0
                    && now - lastAssistantRedirectMs >= 120
                    && now - lastAssistantRedirectMs <= 1800
                    && (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                        || event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) {
                Log.i(TAG, "Suppressing post-redirect Assistant response");
                performGlobalAction(GLOBAL_ACTION_BACK);
                handler.postDelayed(this::bringModTaskToFront, 120);
            }
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
                lastAssistantRedirectMs = now;
                Log.i(TAG, "Play Store intercepted; dismissing it, query=" + query);

                // Remove the Play Store task from the visible stack first. On BRAVIA,
                // Assistant may open Play Store even though the mod is already launched.
                performGlobalAction(GLOBAL_ACTION_BACK);

                final String q = query;
                handler.postDelayed(() -> launchModSearch(q), 140);
                handler.postDelayed(this::bringModTaskToFront, 900);
                return;
            }

            // Fallback for cases where Katniss transcript arrives slightly later.
            handler.removeCallbacks(checkPlayStoreRunnable);
            handler.postDelayed(checkPlayStoreRunnable, 250);
            handler.postDelayed(checkPlayStoreRetryRunnable, 650);
            handler.postDelayed(checkPlayStoreRetry2Runnable, 1200);
            return;
        }

        // BRAVIA routes Assistant's fallback http://www.youtube.com intent into
        // FrameworkPackageStubs when the Google-signed YouTube package is absent.
        // That stub shows the "app doesn't support this package" message even though
        // the bridge already opened the mod successfully. Dismiss only when this
        // immediately follows a recent YouTube Assistant command.
        if (FRAMEWORK_STUB_PACKAGE.equals(pkg)) {
            long now = SystemClock.elapsedRealtime();
            boolean recentExplicitYoutube =
                    lastAssistantUtteranceMs > 0 && now - lastAssistantUtteranceMs <= 5000;
            boolean recentAnyVoice =
                    lastAssistantAnyUtteranceMs > 0 && now - lastAssistantAnyUtteranceMs <= 15000;
            boolean recentResultClick =
                    lastAssistantClickedTextMs > 0 && now - lastAssistantClickedTextMs <= 5000;

            if (recentExplicitYoutube || recentAnyVoice || recentResultClick) {
                String query = getRecentAssistantQuery(now);
                Log.i(TAG, "Framework YouTube stub intercepted; query=" + query);
                performGlobalAction(GLOBAL_ACTION_BACK);

                if (!query.isEmpty()) {
                    lastAssistantRedirectMs = now;
                    final String q = query;
                    handler.postDelayed(() -> launchModSearch(q), 100);
                    handler.postDelayed(this::bringModTaskToFront, 650);
                } else {
                    handler.postDelayed(this::bringModTaskToFront, 120);
                }
                return;
            }
        }

        // Assistant sometimes sends HOME at the end of the voice interaction after
        // the mod has already started. During a short grace window, immediately
        // restore the existing YouTube mod task without re-dispatching the search.
        if (LAUNCHER_PACKAGE.equals(pkg)) {
            long now = SystemClock.elapsedRealtime();
            if (lastAssistantRedirectMs > 0
                    && now - lastAssistantRedirectMs >= 400
                    && now - lastAssistantRedirectMs <= 6500) {
                Log.i(TAG, "Launcher appeared after Assistant redirect; restoring mod task");
                handler.postDelayed(this::bringModTaskToFront, 180);
            }
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

        // Remember the user's current voice query even if it does not contain
        // "YouTube". This supports flows such as saying "karaoke chân ái",
        // reviewing Assistant's video results, then selecting one.
        String anySpoken = bestTextFromEvent(event, false);
        if (isUsefulSpokenQuery(anySpoken)) {
            rememberGenericVoiceCandidate(anySpoken, now);
        }

        String spoken = bestTextFromEvent(event, true);
        if (!spoken.isEmpty() && spoken.toLowerCase(Locale.ROOT).contains("youtube")) {
            lastAssistantUtterance = spoken.trim();
            lastAssistantUtteranceMs = now;

            // Only explicit YouTube commands auto-launch. Generic searches remain
            // inside Assistant until the user actually selects a video result.
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
        return !isGenericUiLabel(lower)
                && !(lower.equals("mở")
                || lower.equals("phát"));
    }

    private void rememberGenericVoiceCandidate(String raw, long now) {
        String candidate = raw == null ? "" : raw.trim();
        String cleaned = sanitizeQuery(candidate);
        if (cleaned.isEmpty() || isGenericUiLabel(cleaned)) return;

        // Start a new voice window after a quiet gap.
        if (lastAssistantAnyUtterance.isEmpty()
                || now - lastAssistantAnyUtteranceMs > 4500) {
            lastAssistantAnyUtterance = candidate;
            lastAssistantAnyUtteranceMs = now;
            Log.i(TAG, "Katniss generic voice start=" + lastAssistantAnyUtterance);
            return;
        }

        // During recognition only accept a natural extension/contraction of the
        // same phrase. This lets "karaoke" -> "karaoke chân" -> "karaoke chân ái"
        // evolve, while result titles/section labels cannot overwrite the query.
        String oldNorm = sanitizeQuery(lastAssistantAnyUtterance)
                .toLowerCase(Locale.ROOT);
        String newNorm = cleaned.toLowerCase(Locale.ROOT);

        boolean samePhraseEvolution = newNorm.startsWith(oldNorm)
                || oldNorm.startsWith(newNorm)
                || newNorm.contains(oldNorm);

        if (samePhraseEvolution) {
            if (newNorm.length() >= oldNorm.length()) {
                lastAssistantAnyUtterance = candidate;
            }
            lastAssistantAnyUtteranceMs = now;
            Log.i(TAG, "Katniss generic voice update=" + lastAssistantAnyUtterance);
        }
    }

    private boolean isGenericUiLabel(String value) {
        if (value == null) return true;
        String lower = value.trim().toLowerCase(Locale.ROOT);
        return lower.equals("youtube")
                || lower.equals("video")
                || lower.equals("videos")
                || lower.equals("video clip")
                || lower.equals("video clips")
                || lower.equals("kết quả video")
                || lower.equals("các video")
                || lower.equals("xem thêm")
                || lower.equals("more")
                || lower.equals("results")
                || lower.equals("search results");
    }

    private boolean isUsefulSpokenQuery(String value) {
        if (value == null) return false;
        String t = value.trim();
        if (t.length() < 2 || t.length() > 120) return false;
        if (isGenericUiLabel(t)) return false;

        String lower = t.toLowerCase(Locale.ROOT);
        // Exclude common Assistant chrome/status text that can appear in the
        // accessibility tree and should never become a YouTube search query.
        return !(lower.contains("đang nghe")
                || lower.contains("thử nói")
                || lower.contains("google assistant")
                || lower.contains("kết quả tìm kiếm")
                || lower.startsWith("nhấn "));
    }

    private String getRecentAssistantQuery(long now) {
        // Explicit YouTube voice command has highest priority.
        if (!lastAssistantUtterance.isEmpty() && now - lastAssistantUtteranceMs <= 9000) {
            String q = sanitizeQuery(lastAssistantUtterance);
            if (!q.isEmpty() && !isGenericUiLabel(q)) return q;
        }

        // Next prefer the original spoken query even when it did not say YouTube.
        // This prevents Assistant section labels such as "Videos" from replacing
        // a query like "karaoke chân ái" after the user selects a video result.
        if (!lastAssistantAnyUtterance.isEmpty() && now - lastAssistantAnyUtteranceMs <= 15000) {
            String q = sanitizeQuery(lastAssistantAnyUtterance);
            if (!q.isEmpty() && !isGenericUiLabel(q)) return q;
        }

        // Last resort: clicked result text, but never generic UI labels.
        if (!lastAssistantClickedText.isEmpty() && now - lastAssistantClickedTextMs <= 6000) {
            String q = sanitizeQuery(lastAssistantClickedText);
            if (!q.isEmpty() && !isGenericUiLabel(q)) return q;
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

    private void bringModTaskToFront() {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(MOD_PACKAGE);
            if (intent == null) return;

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            Log.i(TAG, "Brought existing mod task to foreground");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to bring mod task to foreground", t);
        }
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
            lastAssistantRedirectMs = now;
            Log.i(TAG, "Delayed Play Store redirect query=" + query);
            performGlobalAction(GLOBAL_ACTION_BACK);
            final String q = query;
            handler.postDelayed(() -> launchModSearch(q), 140);
            handler.postDelayed(this::bringModTaskToFront, 900);
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
