package com.ikofi.sshd;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

import com.ikofi.sshd.settings.Prefs;

/**
 * Handle start-on-boot.
 */
public class BootReceiver
        extends BroadcastReceiver {

    public void onReceive(@NonNull final Context context,
                          @NonNull final Intent intent) {

        final String action = intent.getAction();
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
            || Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            if (Prefs.isStartOnBoot(context)) {
                SshdService.startService(context, StartMode.OnBoot);
            }
        }
    }
}
