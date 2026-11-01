package ru.vesta.hudbridge;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.hoho.android.usbserial.driver.UsbSerialDriver;

import java.util.List;

public class MainActivity extends Activity {

    private TextView statusTv;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36, 24, 36, 24);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Vesta HUD Bridge v3.0 (Неубиваемый фон + Все знаки)");
        title.setTextSize(19f);
        root.addView(title);

        statusTv = new TextView(this);
        statusTv.setTextSize(14f);
        statusTv.setPadding(0, 14, 0, 14);
        root.addView(statusTv);

        Button btnPerm = new Button(this);
        btnPerm.setText("1. Уведомления Android (проверить тумблер)");
        btnPerm.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        root.addView(btnPerm);

        Button btnAcc = new Button(this);
        btnAcc.setText("2. Спец. возможности -> [Vesta HUD Чтение экрана]");
        btnAcc.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(btnAcc);

        Button btnUsb = new Button(this);
        btnUsb.setText("3. Подключить USB ESP32-S3 (и запомнить навсегда)");
        btnUsb.setOnClickListener(v -> requestUsbPermissionNow());
        root.addView(btnUsb);

        Button btnTest = new Button(this);
        btnTest.setText("4. ТЕСТ: [Круто направо] + [Знак 60]");
        btnTest.setOnClickListener(v -> {
            if (YandexHudService.instance != null) {
                YandexHudService.instance.sendToEsp32("$Y,0 Ограничение 60;\n$Y,70 м Круто направо;\n");
            }
        });
        root.addView(btnTest);

        setContentView(scroll);

        Intent srv = new Intent(this, YandexHudService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(srv);
        } else {
            startService(srv);
        }

        requestIgnoreBatteryOptimizations();
        requestUsbPermissionNow();

        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                String notifState = YandexHudService.isListenerConnected
                        ? "АКТИВЕН"
                        : "Переподключение...";
                String accState = YandexAccessibilityService.isRunning
                        ? "АКТИВНО (читает полный экран)"
                        : "ВЫКЛ (включи в п.2 или через ADB)";
                statusTv.setText(
                        "Шторка уведомлений: " + notifState + "\n" +
                        "Полный экран Яндекса: " + accState + "\n" +
                        "Экран Яндекса сейчас: " + YandexAccessibilityService.lastScreenDebug + "\n" +
                        YandexHudService.lastStatusLog
                );
                uiHandler.postDelayed(this, 700);
            }
        });
    }

    private void requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                }
            } catch (Exception ignored) {}
        }
    }

    private void requestUsbPermissionNow() {
        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (manager == null) return;
        List<UsbSerialDriver> drivers = YandexHudService.getCustomProber().findAllDrivers(manager);
        if (!drivers.isEmpty()) {
            UsbSerialDriver d = drivers.get(0);
            if (!manager.hasPermission(d.getDevice())) {
                Intent permIntent = new Intent(YandexHudService.ACTION_USB_PERMISSION);
                permIntent.setPackage(getPackageName());
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent pi = PendingIntent.getBroadcast(this, 0, permIntent, flags);
                manager.requestPermission(d.getDevice(), pi);
            } else if (YandexHudService.instance != null) {
                YandexHudService.instance.ensureUsbConnected();
            }
        }
    }
}