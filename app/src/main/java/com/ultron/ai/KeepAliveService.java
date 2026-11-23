package com.ultron.ai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** Keeps ULTRON alive (and allowed to use the microphone) while you are in other apps. */
public class KeepAliveService extends Service {
    private static final String CHANNEL = "ultron_active";

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "ULTRON active", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("ULTRON is active")
                .setContentText("Tap to open. Say a command anytime.")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(1, n);
            }
        } catch (Exception e) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
