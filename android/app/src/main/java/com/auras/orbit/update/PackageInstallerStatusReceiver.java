package com.auras.orbit.update;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.widget.Toast;

/** Handles the system confirmation screen and installation failures for app updates. */
public final class PackageInstallerStatusReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation = readConfirmationIntent(intent);
            if (confirmation != null) {
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirmation);
            }
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Toast.makeText(context, "App update failed" + (message == null ? "." : ": " + message),
                    Toast.LENGTH_LONG).show();
        }
    }

    private static Intent readConfirmationIntent(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
        }
        return readLegacyConfirmationIntent(intent);
    }

    /** API 23–32 require the untyped Parcelable getter for the installer confirmation screen. */
    @SuppressWarnings("deprecation")
    private static Intent readLegacyConfirmationIntent(Intent intent) {
        return (Intent) intent.getParcelableExtra(Intent.EXTRA_INTENT);
    }
}
