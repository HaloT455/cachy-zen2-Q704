package com.halo.alicetvadblock;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQ_VPN = 1001;
    private TextView status;
    private Button youtubeButton;
    private Button ocrButton;
    private Button muteButton;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(64, 48, 64, 48);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Alice TV AdBlock");
        title.setTextSize(30f);
        title.setPadding(0, 0, 0, 28);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setTextSize(18f);
        status.setPadding(0, 0, 0, 24);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        Button start = button("BẬT CHẶN QUẢNG CÁO");
        start.setOnClickListener(v -> requestVpn());
        root.addView(start);

        Button stop = button("TẮT CHẶN QUẢNG CÁO");
        stop.setOnClickListener(v -> {
            Intent i = new Intent(this, AdBlockVpnService.class);
            i.setAction(AdBlockVpnService.ACTION_STOP);
            startService(i);
            refresh();
        });
        root.addView(stop);

        youtubeButton = button("");
        youtubeButton.setOnClickListener(v -> {
            boolean now = !getPreferences().getBoolean("youtube_adskip", true);
            getPreferences().edit().putBoolean("youtube_adskip", now).apply();
            refresh();
        });
        root.addView(youtubeButton);

        ocrButton = button("");
        ocrButton.setOnClickListener(v -> {
            boolean next = !getPreferences().getBoolean("youtube_ocr", true);
            getPreferences().edit().putBoolean("youtube_ocr", next).apply();
            refresh();
        });
        root.addView(ocrButton);

        muteButton = button("");
        muteButton.setOnClickListener(v -> {
            boolean next = !getPreferences().getBoolean("mute_unskippable", false);
            getPreferences().edit().putBoolean("mute_unskippable", next).apply();
            refresh();
        });
        root.addView(muteButton);

        Button access = button("MỞ CÀI ĐẶT ACCESSIBILITY");
        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(access);

        TextView note = new TextView(this);
        note.setText("\nYouTube GỐC được giữ nguyên. AI OCR nhận diện chữ trên màn hình cục bộ, không gửi ảnh đi. Phát hiện nút Skip thì tự bấm. Nếu chọn tắt tiếng, khi nghi là quảng cáo bắt buộc app sẽ tạm mute rồi khôi phục. Không thể gỡ 100% quảng cáo bắt buộc chỉ bằng công cụ bên ngoài.\n\nCấu hình dns.adguard-dns.com của TV không bị ghi đè. VPN DNS là tùy chọn RIÊNG (có thể ảnh hưởng tuyến DNS đang dùng).");
        note.setTextSize(15f);
        root.addView(note, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
        refresh();
    }

    private android.content.SharedPreferences getPreferences() {
        return getSharedPreferences("alice", MODE_PRIVATE);
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(18f);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 8, 0, 8);
        b.setLayoutParams(lp);
        b.setFocusable(true);
        return b;
    }

    private void requestVpn() {
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) {
            startActivityForResult(prepare, REQ_VPN);
        } else {
            startAdBlock();
        }
    }

    private void startAdBlock() {
        Intent i = new Intent(this, AdBlockVpnService.class);
        i.setAction(AdBlockVpnService.ACTION_START);
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        refresh();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN && resultCode == RESULT_OK) startAdBlock();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean requested = getPreferences().getBoolean("vpn_requested", false);
        boolean yt = getPreferences().getBoolean("youtube_adskip", true);
        boolean ocr = getPreferences().getBoolean("youtube_ocr", true);
        boolean mute = getPreferences().getBoolean("mute_unskippable", false);
        status.setText("DNS/VPN runtime: " + (requested ? "ĐÃ BẬT / đang khởi tạo" : "TẮT")
                + "\nYouTube auto-skip: " + (yt ? "BẬT" : "TẮT")
                + "\nNhận diện bằng AI OCR: " + (ocr ? "BẬT" : "TẮT")
                + "\nTự tắt tiếng quảng cáo: " + (mute ? "BẬT" : "TẮT"));
        youtubeButton.setText("YOUTUBE GỐC - AUTO SKIP: " + (yt ? "BẬT" : "TẮT"));
        ocrButton.setText("AI NHẬN DIỆN ẢNH OFFLINE: " + (ocr ? "BẬT" : "TẮT"));
        muteButton.setText("TẮT TIẾNG KHI NGHI LÀ QUẢNG CÁO: " + (mute ? "BẬT" : "TẮT"));
    }
}
