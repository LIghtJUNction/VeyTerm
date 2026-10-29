package com.termux.app.files.ui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/** Parent must declare this unexported dataSync service and FOREGROUND_SERVICE permission. */
public final class FileTaskService extends Service {
    private static final String CHANNEL = "fm_file_tasks";
    private static final int NOTIFICATION = 0x464d;
    private static FileTaskService current;

    @Override public void onCreate() {
        super.onCreate();
        current = this;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "File operations", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try { startForeground(NOTIFICATION, notification()); }
        catch (RuntimeException failure) { FileJobs.get(this).serviceFailed(failure); stopSelf(); return START_NOT_STICKY; }
        if (intent != null && "fm_cancel".equals(intent.getAction())) FileJobs.get(this).cancel();
        FileJobs.get(this).serviceReady();
        if (!FileJobs.get(this).snapshot().running) stopSelf();
        return START_NOT_STICKY;
    }

    private Notification notification() {
        FileJobs.Snapshot state = FileJobs.get(this).snapshot();
        int immutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        Intent open = new Intent(this, FileManagerActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | immutable);
        PendingIntent cancel = PendingIntent.getService(this, 1,
            new Intent(this, FileTaskService.class).setAction("fm_cancel"), PendingIntent.FLAG_UPDATE_CURRENT | immutable);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(state.title).setContentText(state.source == null ? state.progress : "Conflict: open Files to choose an action")
            .setContentIntent(content).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(0, 0, true).addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel).build();
    }

    static void updateIfRunning() {
        if (current != null && FileJobs.get(current).snapshot().running)
            ((NotificationManager) current.getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION, current.notification());
    }

    @Override public void onDestroy() { current = null; stopForeground(true); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
