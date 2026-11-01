package ru.vesta.hudbridge;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        Log.d("BootReceiver", "Событие автозапуска: " + intent.getAction());

        try {
            PackageManager pm = context.getPackageManager();
            ComponentName cn = new ComponentName(context, YandexHudService.class);
            pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);

            ComponentName cnAcc = new ComponentName(context, YandexAccessibilityService.class);
            pm.setComponentEnabledSetting(cnAcc, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);

            Intent srv = new Intent(context, YandexHudService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(srv);
            } else {
                context.startService(srv);
            }
        } catch (Exception e) {
            Log.e("BootReceiver", "Ошибка автозапуска: " + e.getMessage());
        }
    }
}