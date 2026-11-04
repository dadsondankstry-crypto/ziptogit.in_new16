package com.guardiao.dados;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bloqueio local: só os apps selecionados são roteados para a VPN e os pacotes
 * são descartados. NÃO é um proxy, não reencaminha tráfego, não espiona dados.
 * Apps não selecionados continuam fora desta VPN (salvo políticas do sistema).
 */
public class BlockVpnService extends VpnService {
    public static final String ACTION_START = "com.guardiao.dados.START";
    public static final String ACTION_STOP = "com.guardiao.dados.STOP";
    public static final String EXTRA_PACKAGES = "packages";
    private static final int NOTIFICATION_ID = 401;
    private static final String CHANNEL_ID = "guardiao_bloqueio";
    private static final AtomicBoolean ACTIVE = new AtomicBoolean(false);
    private static final AtomicLong BYTES_DISCARDED = new AtomicLong(0);

    private volatile boolean keepRunning = false;
    private ParcelFileDescriptor tunnel;
    private Thread readerThread;

    public static boolean isActive() { return ACTIVE.get(); }
    public static long bytesDiscarded() { return BYTES_DISCARDED.get(); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopBlocking();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;
        ArrayList<String> targets = intent.getStringArrayListExtra(EXTRA_PACKAGES);
        if (targets == null || targets.isEmpty() || VpnService.prepare(this) != null) {
            stopBlocking();
            stopSelf();
            return START_NOT_STICKY;
        }

        createNotificationChannel();
        Notification notification = makeNotification(targets.size());
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (RuntimeException e) {
            Log.e("GuardiaoVpn", "Nao foi possivel iniciar em primeiro plano", e);
            stopSelf();
            return START_NOT_STICKY;
        }

        stopBlocking(); // encerra uma sessão anterior, se existir
        try {
            Builder builder = new Builder()
                .setSession("Guardião de Dados • bloqueio local")
                .setMtu(1500)
                .addAddress("10.253.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addAddress("fd00:253::2", 128)
                .addRoute("::", 0);
            int validCount = 0;
            for (String pkg : targets) {
                if (pkg == null || pkg.equals(getPackageName())) continue;
                try {
                    builder.addAllowedApplication(pkg);
                    validCount++;
                } catch (PackageManager.NameNotFoundException ex) {
                    Log.w("GuardiaoVpn", "Aplicativo foi removido: " + pkg);
                }
            }
            if (validCount == 0) {
                stopForeground(true);
                stopSelf();
                return START_NOT_STICKY;
            }
            tunnel = builder.establish();
            if (tunnel == null) throw new IOException("VPN nao autorizada pelo Android");
            keepRunning = true;
            BYTES_DISCARDED.set(0);
            ACTIVE.set(true);
            readerThread = new Thread(this::consumeDiscardedPackets, "Guardiao-VPN-TUN");
            readerThread.start();
        } catch (Exception e) {
            Log.e("GuardiaoVpn", "Falha ao ativar bloqueio", e);
            stopBlocking();
            stopForeground(true);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void consumeDiscardedPackets() {
        ParcelFileDescriptor local = tunnel;
        if (local == null) return;
        try (FileInputStream incoming = new FileInputStream(local.getFileDescriptor())) {
            byte[] packet = new byte[32767];
            while (keepRunning) {
                int length = incoming.read(packet);
                if (length < 0) break;
                if (length > 0) BYTES_DISCARDED.addAndGet(length);
                // NADA é enviado para a internet: o pacote é descartado.
            }
        } catch (IOException e) {
            if (keepRunning) Log.w("GuardiaoVpn", "Interface VPN foi encerrada", e);
        } finally {
            keepRunning = false;
            ACTIVE.set(false);
        }
    }

    private void stopBlocking() {
        keepRunning = false;
        ACTIVE.set(false);
        if (tunnel != null) {
            try { tunnel.close(); } catch (IOException ignored) {}
            tunnel = null;
        }
        readerThread = null;
    }

    @Override public void onRevoke() {
        stopBlocking();
        stopSelf();
        super.onRevoke();
    }

    @Override public void onDestroy() {
        stopBlocking();
        super.onDestroy();
    }

    private void createNotificationChannel() {
        NotificationManager mgr = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel c = new NotificationChannel(CHANNEL_ID,
            "Bloqueio de aplicativos", NotificationManager.IMPORTANCE_LOW);
        c.setDescription("Mostra quando o bloqueio local está ativo");
        mgr.createNotificationChannel(c);
    }

    private Notification makeNotification(int count) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, BlockVpnService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 1, stop,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Guardião de Dados • bloqueio ativo")
            .setContentText(count + " app(s) selecionado(s) sem internet")
            .setContentIntent(openPending)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(new Notification.Action.Builder(
                R.drawable.ic_notification, "Desativar", stopPending).build())
            .build();
    }
}
