package com.halo.alicetvadblock;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.Executor;

public class YouTubeAdSkipService extends AccessibilityService {
    private static final String TAG = "AliceYTAdAI";
    private static final String YOUTUBE = "com.google.android.youtube.tv";
    private static final long OCR_INTERVAL_MS = 1400;
    private static final long CLICK_COOLDOWN_MS = 1900;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextRecognizer recognizer;
    private AudioManager audio;
    private boolean youtubeForeground;
    private boolean scanning;
    private boolean mutedByUs;
    private int savedVolume = -1;
    private int detectedAdFrames;
    private long lastAdEvidenceMs;
    private long lastClickMs;
    private long lastFailureLogMs;
    private long scans;
    private long ocrSkips;

    private final Runnable scanTask = new Runnable() {
        @Override public void run() {
            if (!youtubeForeground || !enabled()) return;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) clickAccessibleSkip(root);

            if (getSharedPreferences("alice", MODE_PRIVATE)
                    .getBoolean("youtube_ocr", true)) {
                scanScreenshot();
            }

            if (mutedByUs && SystemClock.elapsedRealtime() - lastAdEvidenceMs > 3700) {
                restoreVolume("no ad evidence");
            }
            handler.postDelayed(this, OCR_INTERVAL_MS);
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        // Recover from a service restart while we had muted media.
        if (getSharedPreferences("alice", MODE_PRIVATE).getBoolean("mute_active", false)) {
            int saved = getSharedPreferences("alice", MODE_PRIVATE)
                    .getInt("old_volume", -1);
            if (saved >= 0 && audio != null && audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                try { audio.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0); }
                catch (RuntimeException e) { Log.w(TAG, "Volume recovery failed", e); }
            }
            getSharedPreferences("alice", MODE_PRIVATE).edit()
                    .putBoolean("mute_active", false).apply();
        }
        Log.i(TAG, "V3 on-device ML OCR ready; stock YouTube only");
    }

    private boolean enabled() {
        return getSharedPreferences("alice", MODE_PRIVATE)
                .getBoolean("youtube_adskip", true);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String p = pkg.toString();

        if (YOUTUBE.equals(p) && enabled()) {
            if (!youtubeForeground) {
                youtubeForeground = true;
                detectedAdFrames = 0;
                lastAdEvidenceMs = 0;
                Log.i(TAG, "YouTube foreground; OCR active");
                handler.removeCallbacks(scanTask);
                handler.post(scanTask);
            }
            if (SystemClock.elapsedRealtime() - lastClickMs >= CLICK_COOLDOWN_MS) {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null) clickAccessibleSkip(root);
            }
        } else if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && youtubeForeground
                && !p.equals(getPackageName())) {
            youtubeForeground = false;
            handler.removeCallbacks(scanTask);
            restoreVolume("left YouTube");
            Log.i(TAG, "YouTube background; OCR paused (" + p + ")");
        }
    }

    private void clickAccessibleSkip(AccessibilityNodeInfo root) {
        if (SystemClock.elapsedRealtime() - lastClickMs < CLICK_COOLDOWN_MS) return;
        ArrayDeque<AccessibilityNodeInfo> nodes = new ArrayDeque<>();
        nodes.add(root);
        int count = 0;
        while (!nodes.isEmpty() && count++ < 550) {
            AccessibilityNodeInfo n = nodes.removeFirst();
            String label = safe(n.getText()) + " " + safe(n.getContentDescription());
            if (isSkipLabel(label)) {
                AccessibilityNodeInfo clickable = n;
                for (int i = 0; i < 5 && clickable != null; i++, clickable = clickable.getParent()) {
                    if (clickable.isVisibleToUser() && clickable.isClickable()
                            && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        lastClickMs = SystemClock.elapsedRealtime();
                        Log.i(TAG, "SKIP_UI success label=" + truncate(label));
                        restoreVolume("clicked skip");
                        return;
                    }
                }
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                if (!r.isEmpty()) {
                    tapSkip(r.centerX(), r.centerY(), "node");
                    return;
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo child = n.getChild(i);
                if (child != null) nodes.addLast(child);
            }
        }
    }

    private void scanScreenshot() {
        if (Build.VERSION.SDK_INT < 30 || scanning || recognizer == null) return;
        scanning = true;
        scans++;
        Executor main = getMainExecutor();
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, main, new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult result) {
                    Bitmap bitmap = null;
                    try {
                        HardwareBuffer buffer = result.getHardwareBuffer();
                        if (buffer == null) {
                            scanning = false;
                            return;
                        }
                        Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                        if (hardware != null) {
                            Bitmap copied = hardware.copy(Bitmap.Config.ARGB_8888, false);
                            hardware.recycle();
                            if (copied != null) {
                                int w = copied.getWidth();
                                if (w > 1440) {
                                    int h = Math.max(1, (int)((double)copied.getHeight() * 1440 / w));
                                    bitmap = Bitmap.createScaledBitmap(copied, 1440, h, true);
                                    copied.recycle();
                                } else bitmap = copied;
                            }
                        }
                        buffer.close();
                    } catch (Throwable t) {
                        scanning = false;
                        logFailure("screenshot bitmap", t);
                        return;
                    }

                    if (bitmap == null) {
                        scanning = false;
                        return;
                    }
                    final Bitmap image = bitmap;
                    try {
                        recognizer.process(InputImage.fromBitmap(image, 0))
                            .addOnSuccessListener(text -> inspectText(text, image.getWidth(), image.getHeight()))
                            .addOnFailureListener(e -> logFailure("OCR", e))
                            .addOnCompleteListener(task -> {
                                image.recycle();
                                scanning = false;
                            });
                    } catch (Throwable t) {
                        image.recycle();
                        scanning = false;
                        logFailure("OCR dispatch", t);
                    }
                }
                @Override public void onFailure(int errorCode) {
                    scanning = false;
                    if (SystemClock.elapsedRealtime() - lastFailureLogMs > 7000) {
                        lastFailureLogMs = SystemClock.elapsedRealtime();
                        Log.w(TAG, "Screenshot unavailable code=" + errorCode
                                + " (secure Cobalt surfaces may block capture)");
                    }
                }
            });
        } catch (Throwable t) {
            scanning = false;
            logFailure("takeScreenshot", t);
        }
    }

    private void inspectText(Text ocr, int width, int height) {
        if (!youtubeForeground || !enabled()) return;
        boolean adEvidence = false;
        Rect skipRect = null;
        String evidenceText = "";

        for (Text.TextBlock block : ocr.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String label = normalize(line.getText());
                Rect r = line.getBoundingBox();
                if (r == null || r.isEmpty()) continue;

                if (isSkipLabel(label)
                        && r.centerX() > width * 0.35
                        && r.centerY() > height * 0.27
                        && r.width() < width * 0.5) {
                    skipRect = r;
                    adEvidence = true;
                    evidenceText = label;
                    break;
                }
                if (isAdEvidence(label)) {
                    adEvidence = true;
                    evidenceText = label;
                }
            }
            if (skipRect != null) break;
        }

        long now = SystemClock.elapsedRealtime();
        if (adEvidence) {
            lastAdEvidenceMs = now;
            detectedAdFrames = Math.min(10, detectedAdFrames + 1);
            if (detectedAdFrames == 1 || detectedAdFrames % 5 == 0) {
                Log.i(TAG, "OCR_AD_DETECTED label=" + truncate(evidenceText)
                        + " consecutive=" + detectedAdFrames
                        + " scans=" + scans);
            }
        } else {
            detectedAdFrames = 0;
        }

        if (skipRect != null && now - lastClickMs >= CLICK_COOLDOWN_MS) {
            int x = Math.round(skipRect.centerX() * getResources().getDisplayMetrics().widthPixels
                    / (float)width);
            int y = Math.round(skipRect.centerY() * getResources().getDisplayMetrics().heightPixels
                    / (float)height);
            tapSkip(x, y, "OCR");
        } else if (detectedAdFrames >= 2
                && getSharedPreferences("alice", MODE_PRIVATE)
                    .getBoolean("mute_unskippable", false)) {
            silenceAd();
        }
    }

    private void tapSkip(int x, int y, String origin) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastClickMs < CLICK_COOLDOWN_MS || !youtubeForeground) return;
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 70)).build();
        boolean dispatched = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) {
                Log.i(TAG, "SKIP_TAP completed origin=" + origin + " x=" + x + " y=" + y);
            }
            @Override public void onCancelled(GestureDescription g) {
                Log.w(TAG, "SKIP_TAP cancelled origin=" + origin);
            }
        }, handler);
        if (dispatched) {
            lastClickMs = now;
            ocrSkips++;
            Log.i(TAG, "SKIP_TAP requested origin=" + origin + " count=" + ocrSkips);
            restoreVolume("attempted skip");
        }
    }

    private boolean isSkipLabel(String raw) {
        String s = normalize(raw);
        return s.equals("skip ad") || s.equals("skip ads")
                || s.equals("skip advertisements") || s.equals("skip advertisement")
                || s.equals("bỏ qua quảng cáo") || s.equals("bo qua quang cao")
                || s.equals("bỏ qua qc") || s.equals("bo qua qc")
                || s.startsWith("skip ad ") && s.length() < 26
                || s.startsWith("bỏ qua quảng cáo ") && s.length() < 34;
    }

    private boolean isAdEvidence(String label) {
        String s = normalize(label);
        return s.matches(".*\\bads?\\s+[1-9]\\s*(of|/)\\s*[1-9].*")
                || s.matches(".*quảng cáo\\s*[1-9]\\s*(trên|/)\\s*[1-9].*")
                || s.contains("video will play after ad")
                || s.contains("video sẽ phát sau quảng cáo")
                || s.equals("advertisement")
                || s.equals("quảng cáo")
                || s.equals("sponsored · ad");
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ").trim();
    }

    private String safe(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    private String truncate(String value) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), 70));
    }

    private void silenceAd() {
        if (mutedByUs || audio == null) return;
        try {
            int volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (volume <= 0) return;
            savedVolume = volume;
            getSharedPreferences("alice", MODE_PRIVATE).edit()
                    .putInt("old_volume", savedVolume)
                    .putBoolean("mute_active", true).apply();
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            mutedByUs = true;
            Log.i(TAG, "AD_MUTED oldMediaVolume=" + savedVolume);
        } catch (Throwable t) {
            logFailure("mute", t);
        }
    }

    private void restoreVolume(String reason) {
        if (!mutedByUs) return;
        mutedByUs = false;
        try {
            if (audio != null && savedVolume >= 0
                    && audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0);
                Log.i(TAG, "AD_UNMUTED reason=" + reason);
            }
        } catch (Throwable t) {
            logFailure("unmute", t);
        } finally {
            getSharedPreferences("alice", MODE_PRIVATE).edit()
                    .putBoolean("mute_active", false).apply();
            savedVolume = -1;
        }
    }

    private void logFailure(String operation, Throwable t) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastFailureLogMs > 5000) {
            lastFailureLogMs = now;
            Log.w(TAG, operation + " failed", t);
        }
    }

    @Override public void onInterrupt() {
        restoreVolume("accessibility interrupted");
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(scanTask);
        youtubeForeground = false;
        restoreVolume("service destroyed");
        if (recognizer != null) recognizer.close();
        super.onDestroy();
    }
}
