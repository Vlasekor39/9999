package ru.vesta.hudbridge;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

public class UsbAttachActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent srv = new Intent(this, YandexHudService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(srv);
        } else {
            startService(srv);
        }
        if (YandexHudService.instance != null) {
            YandexHudService.instance.ensureUsbConnected();
        }
        finish();
    }
}