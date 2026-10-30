package com.soum91.embertv;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

public final class InstallReceiver extends BroadcastReceiver {
    private static final String CHANNEL = "embertv_install_approval";
    private static final int NOTIFICATION_BASE = 49000;
    private static volatile Intent pendingApproval;

    static void approveWhenVisible(Activity activity) {
        Intent confirm = pendingApproval;
        if (confirm == null) return;
        pendingApproval = null;
        try { activity.startActivity(confirm); }
        catch (Exception e) { MainActivity.report("Open TV notifications to approve installation: " + e.getMessage()); }
    }

    @Override public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        int id = NOTIFICATION_BASE + Math.abs(intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, 0) % 10000);
        NotificationManager notifications = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) { MainActivity.report("Android did not provide an installation confirmation screen"); return; }
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            pendingApproval = confirm;
            // Android blocks launching activities from background receivers on
            // recent OS versions. A notification gives the TV user a deliberate
            // way to approve without requiring EmberTV to remain foregrounded.
            if (MainActivity.isVisible()) {
                try {
                    context.startActivity(confirm);
                    pendingApproval = null;
                    MainActivity.report("Approve installation on the TV");
                    return;
                } catch (Exception ignored) { }
            }
            notifications.createNotificationChannel(
                new NotificationChannel(CHANNEL, "EmberTV install approvals", NotificationManager.IMPORTANCE_HIGH));
            PendingIntent approval = PendingIntent.getActivity(context, id, confirm,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.embertv_icon)
                .setContentTitle("Approve APK installation")
                .setContentText("Select to confirm the installation on your TV")
                .setContentIntent(approval)
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .build();
            notifications.notify(id, notification);
            MainActivity.report("Installation ready. Open TV notifications to approve.");
        } else {
            pendingApproval = null;
            notifications.cancel(id);
            if (status == PackageInstaller.STATUS_SUCCESS)
                MainActivity.report("APK installed successfully");
            else
                MainActivity.report("Android installer: " +
                    intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) + " (code " + status + ")");
        }
    }
}
