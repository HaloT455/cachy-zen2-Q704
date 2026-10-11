package com.halo.alicetvadblock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class AdBlockVpnService extends VpnService implements Runnable {
    public static final String ACTION_START = "com.halo.alicetvadblock.START";
    public static final String ACTION_STOP = "com.halo.alicetvadblock.STOP";

    private static final int NOTIFY_ID = 7601;
    private static final String CHANNEL_ID = "alice_tv_adblock";
    private static final String VPN_IP = "10.77.0.1";
    private static final String DNS_IP = "10.77.0.2";
    private static final String UPSTREAM_DNS = "94.140.14.14";

    private volatile boolean running;
    private ParcelFileDescriptor tun;
    private Thread worker;

    private static final Set<String> BLOCK_SUFFIXES = new HashSet<>(Arrays.asList(
            "doubleclick.net",
            "googleadservices.com",
            "googlesyndication.com",
            "adservice.google.com",
            "adservice.google.com.vn",
            "imasdk.googleapis.com",
            "googletagservices.com",
            "googletagmanager.com",
            "google-analytics.com",
            "app-measurement.com",
            "adnxs.com",
            "adsrvr.org",
            "rubiconproject.com",
            "pubmatic.com",
            "openx.net",
            "criteo.com",
            "criteo.net",
            "taboola.com",
            "outbrain.com",
            "scorecardresearch.com",
            "quantserve.com",
            "amazon-adsystem.com",
            "moatads.com",
            "advertising.com",
            "yieldmo.com",
            "yieldlab.net",
            "smartadserver.com",
            "casalemedia.com",
            "lijit.com",
            "sharethrough.com",
            "contextweb.com",
            "demdex.net",
            "everesttech.net",
            "adsafeprotected.com",
            "unityads.unity3d.com",
            "ads.unity3d.com",
            "applovin.com",
            "applvn.com",
            "supersonicads.com",
            "ironsrc.com",
            "chartboost.com",
            "vungle.com",
            "mopub.com",
            "inmobi.com",
            "startappservice.com",
            "startappexchange.com",
            "adcolony.com",
            "fyber.com",
            "smaato.net",
            "smaato.com",
            "tapjoy.com",
            "adjust.com",
            "branch.io",
            "kochava.com",
            "singular.net",
            "appsflyer.com",
            "flurry.com",
            "segment.io",
            "segment.com",
            "mixpanel.com",
            "hotjar.com",
            "mouseflow.com",
            "clarity.ms",
            "ads.yahoo.com",
            "gemini.yahoo.com",
            "serving-sys.com",
            "2mdn.net",
            "innovid.com",
            "spotxchange.com",
            "spotx.tv",
            "freewheel.tv",
            "fwmrm.net"
    ));

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            getSharedPreferences("alice", MODE_PRIVATE)
                    .edit().putBoolean("vpn_requested", false).apply();
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }

        getSharedPreferences("alice", MODE_PRIVATE)
                .edit().putBoolean("vpn_requested", true).apply();

        startForegroundCompat();
        if (!running) startVpn();
        return START_STICKY;
    }

    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Alice TV AdBlock", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Runtime DNS ad blocking");
            nm.createNotificationChannel(ch);
        }

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        Notification n = b.setContentTitle("Alice TV AdBlock")
                .setContentText("Đang lọc quảng cáo DNS")
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setOngoing(true)
                .build();

        startForeground(NOTIFY_ID, n);
    }

    private synchronized void startVpn() {
        if (running) return;
        try {
            Builder b = new Builder()
                    .setSession("Alice TV AdBlock")
                    .setMtu(1500)
                    .addAddress(VPN_IP, 32)
                    .addDnsServer(DNS_IP)
                    .addRoute(DNS_IP, 32);

            if (Build.VERSION.SDK_INT >= 29) {
                b.setMetered(false);
            }

            tun = b.establish();
            if (tun == null) {
                stopSelf();
                return;
            }

            running = true;
            worker = new Thread(this, "AliceDnsVpn");
            worker.start();
        } catch (Throwable t) {
            running = false;
            stopSelf();
        }
    }

    @Override
    public void run() {
        byte[] packet = new byte[32767];

        try (FileInputStream in = new FileInputStream(tun.getFileDescriptor());
             FileOutputStream out = new FileOutputStream(tun.getFileDescriptor())) {

            while (running) {
                int len = in.read(packet);
                if (len <= 0) continue;

                byte[] response = handlePacket(packet, len);
                if (response != null) {
                    out.write(response);
                    out.flush();
                }
            }
        } catch (Throwable ignored) {
        } finally {
            shutdown();
        }
    }

    private byte[] handlePacket(byte[] p, int len) {
        if (len < 28) return null;

        int version = (p[0] >> 4) & 0x0F;
        if (version != 4) return null;

        int ihl = (p[0] & 0x0F) * 4;
        if (ihl < 20 || len < ihl + 8 + 12) return null;
        if ((p[9] & 0xFF) != 17) return null;

        int udp = ihl;
        int srcPort = u16(p, udp);
        int dstPort = u16(p, udp + 2);
        if (dstPort != 53) return null;

        int dnsOffset = udp + 8;
        int dnsLen = len - dnsOffset;
        if (dnsLen < 12) return null;

        DnsQuestion q = parseQuestion(p, dnsOffset, dnsLen);
        if (q == null) return wrapDnsResponse(p, len, buildServFail(p, dnsOffset, dnsLen), srcPort);

        byte[] dnsResponse;
        if (isBlocked(q.name)) {
            dnsResponse = buildNxdomain(p, dnsOffset, dnsLen, q.questionEnd);
        } else {
            dnsResponse = forwardDns(p, dnsOffset, dnsLen);
            if (dnsResponse == null) {
                dnsResponse = buildServFail(p, dnsOffset, dnsLen);
            }
        }

        return wrapDnsResponse(p, len, dnsResponse, srcPort);
    }

    private byte[] forwardDns(byte[] p, int off, int len) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            if (!protect(socket)) return null;
            socket.setSoTimeout(2500);

            byte[] payload = Arrays.copyOfRange(p, off, off + len);
            DatagramPacket req = new DatagramPacket(
                    payload, payload.length, InetAddress.getByName(UPSTREAM_DNS), 53);
            socket.send(req);

            byte[] buf = new byte[4096];
            DatagramPacket resp = new DatagramPacket(buf, buf.length);
            socket.receive(resp);
            return Arrays.copyOf(resp.getData(), resp.getLength());
        } catch (Throwable t) {
            return null;
        } finally {
            if (socket != null) socket.close();
        }
    }

    private boolean isBlocked(String name) {
        if (name == null || name.isEmpty()) return false;
        String d = name.toLowerCase(Locale.ROOT);
        for (String suffix : BLOCK_SUFFIXES) {
            if (d.equals(suffix) || d.endsWith("." + suffix)) return true;
        }
        return false;
    }

    private DnsQuestion parseQuestion(byte[] p, int dnsOff, int dnsLen) {
        if (dnsLen < 12) return null;
        int qd = u16(p, dnsOff + 4);
        if (qd < 1) return null;

        int pos = dnsOff + 12;
        int end = dnsOff + dnsLen;
        StringBuilder name = new StringBuilder();

        while (pos < end) {
            int n = p[pos++] & 0xFF;
            if (n == 0) break;
            if ((n & 0xC0) != 0 || n > 63 || pos + n > end) return null;
            if (name.length() > 0) name.append('.');
            for (int i = 0; i < n; i++) {
                int c = p[pos++] & 0xFF;
                if (c >= 32 && c <= 126) name.append((char)c);
                else name.append('?');
            }
        }

        if (pos + 4 > end) return null;
        int questionEnd = pos + 4;
        return new DnsQuestion(name.toString(), questionEnd - dnsOff);
    }

    private byte[] buildNxdomain(byte[] p, int off, int len, int questionEnd) {
        int outLen = Math.max(12, questionEnd);
        byte[] out = Arrays.copyOfRange(p, off, off + outLen);
        if (out.length < 12) return buildServFail(p, off, len);

        int rd = out[2] & 0x01;
        out[2] = (byte)(0x80 | rd);
        out[3] = (byte)0x83;
        out[6] = out[7] = 0;
        out[8] = out[9] = 0;
        out[10] = out[11] = 0;
        return out;
    }

    private byte[] buildServFail(byte[] p, int off, int len) {
        int copy = Math.min(len, 512);
        byte[] out = Arrays.copyOfRange(p, off, off + copy);
        if (out.length < 12) {
            out = new byte[12];
            if (len >= 2) {
                out[0] = p[off];
                out[1] = p[off + 1];
            }
        }
        int rd = out[2] & 0x01;
        out[2] = (byte)(0x80 | rd);
        out[3] = (byte)0x82;
        out[6] = out[7] = 0;
        out[8] = out[9] = 0;
        out[10] = out[11] = 0;
        return out;
    }

    private byte[] wrapDnsResponse(byte[] original, int originalLen, byte[] dns, int clientPort) {
        if (dns == null) return null;

        int total = 20 + 8 + dns.length;
        byte[] out = new byte[total];

        out[0] = 0x45;
        out[1] = 0;
        put16(out, 2, total);
        out[4] = original[4];
        out[5] = original[5];
        out[6] = 0;
        out[7] = 0;
        out[8] = 64;
        out[9] = 17;
        out[10] = 0;
        out[11] = 0;

        System.arraycopy(original, 16, out, 12, 4);
        System.arraycopy(original, 12, out, 16, 4);

        put16(out, 20, 53);
        put16(out, 22, clientPort);
        put16(out, 24, 8 + dns.length);
        put16(out, 26, 0);

        System.arraycopy(dns, 0, out, 28, dns.length);

        int csum = ipv4Checksum(out, 0, 20);
        put16(out, 10, csum);
        return out;
    }

    private int ipv4Checksum(byte[] b, int off, int len) {
        long sum = 0;
        for (int i = 0; i < len; i += 2) {
            int hi = b[off + i] & 0xFF;
            int lo = (i + 1 < len) ? (b[off + i + 1] & 0xFF) : 0;
            sum += (hi << 8) | lo;
            while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return (int)(~sum) & 0xFFFF;
    }

    private static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static void put16(byte[] b, int off, int v) {
        b[off] = (byte)((v >> 8) & 0xFF);
        b[off + 1] = (byte)(v & 0xFF);
    }

    private synchronized void shutdown() {
        running = false;
        try {
            if (tun != null) tun.close();
        } catch (Throwable ignored) {
        }
        tun = null;
        worker = null;
        stopForeground(true);
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        getSharedPreferences("alice", MODE_PRIVATE)
                .edit().putBoolean("vpn_requested", false).apply();
        shutdown();
        stopSelf();
        super.onRevoke();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }

    private static final class DnsQuestion {
        final String name;
        final int questionEnd;
        DnsQuestion(String name, int questionEnd) {
            this.name = name;
            this.questionEnd = questionEnd;
        }
    }
}
