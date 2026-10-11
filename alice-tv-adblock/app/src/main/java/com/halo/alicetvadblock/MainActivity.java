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
            boolean now = !getPreferences().getBoolean("youtube_redirect", true);
            getPreferences().edit().putBoolean("youtube_redirect", now).apply();
            refresh();
        });
        root.addView(youtubeButton);

        Button access = button("MỞ CÀI ĐẶT ACCESSIBILITY");
        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(access);

        TextView note = new TextView(this);
        note.setText("\nDNS riêng hiện có của TV không bị app xóa hay ghi đè.\nYouTube stock không thể lọc 100% bằng DNS; chế độ YouTube sẽ chuyển sang SmartTube nếu máy đã cài.");
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
        boolean yt = getPreferences().getBoolean("youtube_redirect", true);
        status.setText("DNS/VPN runtime: " + (requested ? "ĐÃ BẬT / đang khởi tạo" : "TẮT")
                + "\nYouTube redirect: " + (yt ? "BẬT" : "TẮT"));
        youtubeButton.setText("CHẾ ĐỘ YOUTUBE: " + (yt ? "BẬT" : "TẮT"));
    }
}
