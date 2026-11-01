package ru.vesta.hudbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import com.hoho.android.usbserial.driver.CdcAcmSerialDriver;
import com.hoho.android.usbserial.driver.ProbeTable;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class YandexHudService extends NotificationListenerService {

    private static final String TAG = "YandexHudService";
    public static final String ACTION_USB_PERMISSION = "ru.vesta.USB_PERMISSION";

    public static YandexHudService instance;
    public static volatile boolean isListenerConnected = false;
    public static volatile String lastStatusLog = "Запуск фонового моста...";
    public static volatile String cachedManeuverDir = "";
    public static volatile String cachedStreetName = "";

    private UsbSerialPort serialPort;
    private String lastSentPacket = "";
    private long lastSendTime = 0;
    private boolean usbPermissionPending = false;

    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());
    public static final Pattern DIST_PATTERN = Pattern.compile("(\\d+[.,]?\\d*\\s*(?:м|км|m|km))(?![а-яa-z])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action) || UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                usbPermissionPending = false;
                ensureUsbConnected();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                usbPermissionPending = false;
                closeUsb();
                updateLog("USB кабель отключен. Ожидание подключения...");
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        startUnkillableForeground();
        tryRootAutoGrantPermissions();
        autoHealServices();

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }

        // Ежесекундный сторожевой таймер: держит USB, автовосстанавливает службы и опрашивает шторку
        watchdogHandler.post(new Runnable() {
            private int tick = 0;
            @Override
            public void run() {
                tick++;
                ensureUsbConnected();
                if (tick % 3 == 0) {
                    autoHealServices();
                }
                if (isListenerConnected && !YandexAccessibilityService.isYandexWindowActive) {
                    pollActiveYandexNotifications();
                }
                watchdogHandler.postDelayed(this, 1000);
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        instance = this;
        startUnkillableForeground();
        autoHealServices();
        ensureUsbConnected();
        return START_STICKY;
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        isListenerConnected = true;
        pollActiveYandexNotifications();
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        isListenerConnected = false;
        autoHealServices();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                requestRebind(new ComponentName(this, YandexHudService.class));
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        watchdogHandler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {}
        closeUsb();
        instance = null;
    }

    // Неубиваемый Foreground-сервис: Teyes CC3 НИКОГДА не закроет его при открытии Яндекс.Навигатора!
    private void startUnkillableForeground() {
        try {
            String chId = "vesta_hud_bridge_v3";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                        chId, "Vesta HUD Авто-Мост", NotificationManager.IMPORTANCE_LOW);
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) nm.createNotificationChannel(ch);

                Notification notif = new Notification.Builder(this, chId)
                        .setContentTitle("Vesta HUD Bridge v3.0 активен")
                        .setContentText("Автопередача Яндекс.Навигатора (Фон + Полный экран)")
                        .setSmallIcon(android.R.drawable.stat_sys_data_usb)
                        .setOngoing(true)
                        .build();
                startForeground(101, notif);
            }
        } catch (Exception ignored) {}
    }

    // Попытка автоматически выдать себе права WRITE_SECURE_SETTINGS (если на Teyes есть root/su)
    private void tryRootAutoGrantPermissions() {
        new Thread(() -> {
            try {
                String pkg = getPackageName();
                String[] cmds = {
                    "pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS",
                    "cmd notification allow_listener " + pkg + "/" + YandexHudService.class.getName(),
                    "dumpsys deviceidle whitelist +" + pkg
                };
                for (String c : cmds) {
                    try {
                        Runtime.getRuntime().exec(new String[]{"su", "-c", c}).waitFor();
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }).start();
    }

    // Автоматическое включение Уведомлений и Спец. возможностей без нажатия кнопок!
    public void autoHealServices() {
        // 1. Программно включаем NotificationListener в Settings.Secure
        try {
            ComponentName cnNotif = new ComponentName(this, YandexHudService.class);
            String flatNotif = cnNotif.flattenToString();
            String curNotif = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            if (curNotif == null || !curNotif.contains(flatNotif)) {
                String nextNotif = (curNotif == null || curNotif.isEmpty()) ? flatNotif : curNotif + ":" + flatNotif;
                Settings.Secure.putString(getContentResolver(), "enabled_notification_listeners", nextNotif);
            }
        } catch (Exception ignored) {}

        // 2. Программно включаем AccessibilityService (чтение полного экрана) в Settings.Secure
        try {
            ComponentName cnAcc = new ComponentName(this, YandexAccessibilityService.class);
            String flatAcc = cnAcc.flattenToString();
            String curAcc = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (curAcc == null || !curAcc.contains(flatAcc)) {
                String nextAcc = (curAcc == null || curAcc.isEmpty()) ? flatAcc : curAcc + ":" + flatAcc;
                Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, nextAcc);
            }
            Settings.Secure.putInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (Exception ignored) {}

        // 3. Если шторка отвалилась после сна Teyes — принудительно переподключаем через requestRebind
        if (!isListenerConnected && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                requestRebind(new ComponentName(this, YandexHudService.class));
            } catch (Exception ignored) {}
        }
    }

    private void pollActiveYandexNotifications() {
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active == null) return;
            for (StatusBarNotification sbn : active) {
                onNotificationPosted(sbn);
            }
        } catch (Exception ignored) {}
    }

    public static UsbSerialProber getCustomProber() {
        ProbeTable customTable = UsbSerialProber.getDefaultProbeTable();
        customTable.addProduct(12346, 4097, CdcAcmSerialDriver.class);
        customTable.addProduct(12346, 16385, CdcAcmSerialDriver.class);
        return new UsbSerialProber(customTable);
    }

    public synchronized void ensureUsbConnected() {
        if (serialPort != null && serialPort.isOpen()) {
            return;
        }
        try {
            UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
            if (manager == null) return;

            List<UsbSerialDriver> availableDrivers = getCustomProber().findAllDrivers(manager);
            if (availableDrivers.isEmpty()) {
                return;
            }

            UsbSerialDriver driver = availableDrivers.get(0);
            UsbDevice device = driver.getDevice();

            if (!manager.hasPermission(device)) {
                if (!usbPermissionPending) {
                    usbPermissionPending = true;
                    updateLog("Запрос разрешения USB...");
                    Intent permIntent = new Intent(ACTION_USB_PERMISSION);
                    permIntent.setPackage(getPackageName());
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    PendingIntent pi = PendingIntent.getBroadcast(this, 0, permIntent, flags);
                    manager.requestPermission(device, pi);
                }
                return;
            }

            usbPermissionPending = false;
            UsbDeviceConnection connection = manager.openDevice(device);
            if (connection == null) return;

            serialPort = driver.getPorts().get(0);
            serialPort.open(connection);
            serialPort.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            try {
                serialPort.setDTR(true);
                serialPort.setRTS(true);
            } catch (Exception ignored) {}

            updateLog("USB ПОДКЛЮЧЕН (115200). Готов к приему Яндекса!");
        } catch (Exception e) {
            closeUsb();
        }
    }

    private synchronized void closeUsb() {
        if (serialPort != null) {
            try {
                serialPort.close();
            } catch (Exception ignored) {}
            serialPort = null;
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        isListenerConnected = true;
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        if (pkg == null) return;

        if (!pkg.contains("ru.yandex.yandexnavi") && !pkg.contains("ru.yandex.yandexmaps")) {
            return;
        }

        Notification notif = sbn.getNotification();
        if (notif == null) return;

        String title = "";
        String text = "";
        String subText = "";
        String bigText = "";

        if (notif.extras != null) {
            Bundle extras = notif.extras;
            title = getExtraString(extras, Notification.EXTRA_TITLE);
            text = getExtraString(extras, Notification.EXTRA_TEXT);
            subText = getExtraString(extras, Notification.EXTRA_SUB_TEXT);
            bigText = getExtraString(extras, Notification.EXTRA_BIG_TEXT);
        }

        if (title.isEmpty() && text.isEmpty()) {
            List<String> rvTexts = new ArrayList<>();
            extractFromRemoteViews(notif.bigContentView, rvTexts);
            extractFromRemoteViews(notif.contentView, rvTexts);
            if (!rvTexts.isEmpty()) {
                title = rvTexts.get(0);
                if (rvTexts.size() > 1) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 1; i < rvTexts.size(); i++) {
                        if (sb.length() > 0) sb.append(" ");
                        sb.append(rvTexts.get(i));
                    }
                    text = sb.toString();
                }
            }
        }

        String parsedPacket = parseYandexNotification(title, text, subText, bigText);
        if (parsedPacket != null && !parsedPacket.isEmpty()) {
            sendDeduplicated(parsedPacket);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // ВАЖНО: Больше НЕ шлем "$Y,CLR;" при исчезновении уведомления из шторки!
        // Потому что Яндекс.Навигатор сам удаляет уведомление из шторки в момент,
        // когда ты открываешь его на полный экран!
    }

    private void extractFromRemoteViews(RemoteViews rv, List<String> out) {
        if (rv == null) return;
        try {
            FrameLayout container = new FrameLayout(getApplicationContext());
            View v = rv.apply(getApplicationContext(), container);
            traverseRemoteView(v, out);
        } catch (Exception ignored) {}
    }

    private void traverseRemoteView(View v, List<String> out) {
        if (v == null) return;
        if (v instanceof TextView) {
            CharSequence cs = ((TextView) v).getText();
            if (cs != null) {
                String s = cleanString(cs.toString());
                String low = s.toLowerCase();
                if (!s.isEmpty() && !low.contains("завершить") && !low.equals("настроить") && !out.contains(s)) {
                    out.add(s);
                }
            }
        } else if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                traverseRemoteView(vg.getChildAt(i), out);
            }
        }
    }

    public static String parseYandexNotification(String title, String text, String subText, String bigText) {
        title = cleanString(title);
        text = cleanString(text);
        subText = cleanString(subText);
        bigText = cleanString(bigText);

        if (title.isEmpty() && text.isEmpty()) return null;

        String combinedLower = (title + " " + text).toLowerCase();
        if (combinedLower.contains("поиск gps") ||
            combinedLower.contains("работает в фоне") ||
            combinedLower.contains("скачивается карта")) {
            return null;
        }

        String distance = "";
        String rawRemainder = "";

        Matcher mTitle = DIST_PATTERN.matcher(title);
        Matcher mText = DIST_PATTERN.matcher(text);

        if (mTitle.find()) {
            distance = mTitle.group(1).replace(',', '.');
            String remT = title.replace(mTitle.group(1), "").trim();
            rawRemainder = (remT + " " + text).trim();
        } else if (mText.find()) {
            distance = mText.group(1).replace(',', '.');
            String remTx = text.replace(mText.group(1), "").trim();
            rawRemainder = (title + " " + remTx).trim();
        } else {
            rawRemainder = (title + " " + text).trim();
        }

        String canonicalManeuver = detectDirection(title + " " + text + " " + bigText);
        if (!canonicalManeuver.isEmpty()) {
            cachedManeuverDir = canonicalManeuver;
        }

        // Вычищаем из текста стандартные глаголы и фразы маневров Яндекса ("круто поверните направо" и т.д.),
        // чтобы осталась ТОЛЬКО реальная улица (если она есть), без дубля "Направо круто поверните направо"!
        String cleanStreet = extractPureStreetName(rawRemainder);
        if (!cleanStreet.isEmpty()) {
            cachedStreetName = cleanStreet;
        }

        StringBuilder payload = new StringBuilder();
        if (!distance.isEmpty()) {
            payload.append(distance);
        }
        if (!canonicalManeuver.isEmpty()) {
            if (payload.length() > 0) payload.append(" ");
            payload.append(canonicalManeuver);
        }
        if (!cleanStreet.isEmpty() && !cleanStreet.equalsIgnoreCase(canonicalManeuver)) {
            if (payload.length() > 0) payload.append(" ");
            payload.append(cleanStreet);
        }

        String result = payload.toString().trim();
        if (result.isEmpty()) return null;

        result = result.replace(";", ",");
        return "$Y," + result + ";\n";
    }

    // Вырезает все служебные слова поворотов из строки, оставляя только название улицы или шоссе
    public static String extractPureStreetName(String raw) {
        if (raw == null) return "";
        String s = " " + raw + " ";
        String[] phrasesToRemove = {
            "круто поверните налево", "круто поверните направо",
            "резко поверните налево", "резко поверните направо",
            "плавно поверните налево", "плавно поверните направо",
            "поверните налево", "поверните направо",
            "держитесь левее", "держитесь правее",
            "круто налево", "круто направо",
            "резко налево", "резко направо",
            "плавно налево", "плавно направо",
            "разворот налево", "разворот направо",
            "выполните разворот", "развернитесь", "разворот",
            "круговое движение", "на круговом движении",
            "1-й съезд", "2-й съезд", "3-й съезд", "4-й съезд",
            "первый съезд", "второй съезд", "третий съезд", "четвертый съезд",
            "съезд налево", "съезд направо", "на съезд",
            "почти на месте", "вы прибыли", "конец маршрута", "прибыли", "финиш",
            "продолжайте движение", "двигайтесь прямо",
            "налево", "направо", "левее", "правее", "прямо", "кольцо", "съезд"
        };
        for (String ph : phrasesToRemove) {
            s = s.replaceAll("(?iu)\\b" + Pattern.quote(ph) + "\\b", " ");
        }
        s = s.replaceAll("(?iu)^\\s*(на|в сторону|к|по)\\s+", " ");
        s = cleanString(s);
        if (s.length() < 2) return "";
        return s;
    }

    // Полный классификатор ВСЕХ маневров Яндекс.Навигатора
    public static String detectDirection(String raw) {
        if (raw == null) return "";
        String s = raw.toLowerCase();

        // 1. Финиш / Прибытие
        if (s.contains("почти на месте") || s.contains("прибыли") || s.contains("конец маршрута") || s.contains("точка назначения") || s.contains("финиш")) {
            return "Почти на месте";
        }
        // 2. Разворот
        if (s.contains("разворот налево")) return "Разворот налево";
        if (s.contains("разворот направо")) return "Разворот направо";
        if (s.contains("разверн") || s.contains("разворот")) return "Разворот";

        // 3. Круговое движение (Кольцо со съездами 1..4)
        if (s.contains("1-й съезд") || s.contains("первый съезд")) return "Кольцо 1";
        if (s.contains("2-й съезд") || s.contains("второй съезд")) return "Кольцо 2";
        if (s.contains("3-й съезд") || s.contains("третий съезд")) return "Кольцо 3";
        if (s.contains("4-й съезд") || s.contains("четвертый съезд")) return "Кольцо 4";
        if (s.contains("кругов") || s.contains("кольц")) return "Кольцо";

        // 4. Острые повороты ("Круто направо" / "Круто налево" / "Резко направо" / "Резко налево")
        if (s.contains("круто налево") || s.contains("круто поверните налево") || s.contains("резко налево") || s.contains("резко поверните налево")) {
            return "Круто налево";
        }
        if (s.contains("круто направо") || s.contains("круто поверните направо") || s.contains("резко направо") || s.contains("резко поверните направо")) {
            return "Круто направо";
        }

        // 5. Съезды и развилки
        if (s.contains("съезд налево") || s.contains("на развилке левее")) return "Съезд налево";
        if (s.contains("съезд направо") || s.contains("на развилке правее") || s.contains("на съезд")) return "Съезд направо";

        // 6. Плавные повороты ("Правее" / "Левее")
        if (s.contains("правее") || s.contains("плавно направо") || s.contains("плавно поверните направо")) return "Правее";
        if (s.contains("левее") || s.contains("плавно налево") || s.contains("плавно поверните налево")) return "Левее";

        // 7. Стандартные повороты 90 градусов
        if (s.contains("налево")) return "Налево";
        if (s.contains("направо")) return "Направо";
        if (s.contains("прямо") || s.contains("продолжайте движение")) return "Прямо";

        // 8. Камеры
        if (s.contains("камера") || s.contains("контроль") || s.contains("радар")) return "Камера";
        return "";
    }

    private String getExtraString(Bundle extras, String key) {
        CharSequence cs = extras.getCharSequence(key);
        return cs != null ? cs.toString() : "";
    }

    public static String cleanString(String input) {
        if (input == null) return "";
        return input.replace('\u00A0', ' ')
                    .replace('\n', ' ')
                    .replace('\r', ' ')
                    .replaceAll("\\s+", " ")
                    .trim();
    }

    public void sendDeduplicated(String packet) {
        if (packet == null || packet.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (!packet.equals(lastSentPacket) || (now - lastSendTime > 1200)) {
            lastSentPacket = packet;
            lastSendTime = now;
            sendToEsp32(packet);
        }
    }

    public synchronized void sendToEsp32(String packet) {
        ensureUsbConnected();
        if (serialPort != null && serialPort.isOpen()) {
            try {
                byte[] data = packet.getBytes(StandardCharsets.UTF_8);
                serialPort.write(data, 500);
                updateLog("Отправлено в HUD:\n" + packet.trim());
            } catch (Exception e) {
                updateLog("Переподключение USB...\nПакет: " + packet.trim());
                closeUsb();
            }
        } else {
            updateLog("Ожидание USB ESP32...\nПерехвачено: " + packet.trim());
        }
    }

    private void updateLog(String msg) {
        lastStatusLog = msg;
        Log.d(TAG, msg);
    }
}