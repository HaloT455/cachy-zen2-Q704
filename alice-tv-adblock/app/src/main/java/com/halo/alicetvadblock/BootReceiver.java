package com.halo.alicetvadblock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        boolean wasOn = context.getSharedPreferences("alice", Context.MODE_PRIVATE)
                .getBoolean("vpn_requested", false);
        if (!wasOn) return;

        Intent svc = new Intent(context, AdBlockVpnService.class);
        svc.setAction(AdBlockVpnService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc);
        else context.startService(svc);
    }
}
