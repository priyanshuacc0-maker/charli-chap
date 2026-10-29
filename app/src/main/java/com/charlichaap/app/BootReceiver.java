package com.charlichaap.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/** Brings the order desk back up after the phone restarts, if the owner asked for it. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        try {
            SharedPreferences prefs = context.getSharedPreferences(ServerService.PREFS, Context.MODE_PRIVATE);
            if (!prefs.getBoolean("auto_start", false)) return;
            String password = prefs.getString(ServerService.KEY_PASSWORD, "");
            if (password == null || password.length() < 12) return;

            Intent service = new Intent(context, ServerService.class).setAction(ServerService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (Exception ignored) {
            // If Android refuses, the owner can open the app and tap Start.
        }
    }
}
