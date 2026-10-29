package com.soum91.embertv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

public final class InstallReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { context.startActivity(confirm); MainActivity.report("Approve installation on the TV"); }
                catch (Exception e) { MainActivity.report("Open EmberTV and approve installation: " + e.getMessage()); }
            }
        } else if (status == PackageInstaller.STATUS_SUCCESS) MainActivity.report("APK installed successfully");
        else MainActivity.report("Android installer: " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) + " (code " + status + ")");
    }
}
