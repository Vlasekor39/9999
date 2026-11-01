package ru.vesta.hudbridge;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

public class YandexAccessibilityService extends AccessibilityService {

    public static volatile boolean isRunning = false;
    public static volatile boolean isYandexWindowActive = false;
    public static volatile String lastScreenDebug = "Ожидание окна Яндекса...";

    private final Handler scanHandler = new Handler(Looper.getMainLooper());
    private long lastScanTime = 0;

    private int lastSentSpeedLimit = 0;
    private long lastSpeedLimitSendTime = 0;

    private static class TopRightNumber {
        int value;
        int centerX;
        boolean hasLimitHint;
        TopRightNumber(int value, int centerX, boolean hasLimitHint) {
            this.value = value;
            this.centerX = centerX;
            this.hasLimitHint = hasLimitHint;
        }
    }

    private final Runnable periodicScanner = new Runnable() {
        @Override
        public void run() {
            try {
                scanActiveWindowsForYandex();
            } catch (Exception ignored) {}
            scanHandler.postDelayed(this, 850);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        isRunning = true;
        scanHandler.post(periodicScanner);
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        isRunning = false;
        isYandexWindowActive = false;
        scanHandler.removeCallbacksAndMessages(null);
        return super.onUnbind(intent);
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        isRunning = true;
        if (event == null) return;
        CharSequence pkgCs = event.getPackageName();
        String pkg = pkgCs != null ? pkgCs.toString().toLowerCase() : "";

        if (pkg.contains("yandex")) {
            if (event.getText() != null) {
                for (CharSequence t : event.getText()) {
                    if (t != null) {
                        String dir = YandexHudService.detectDirection(t.toString());
                        if (!dir.isEmpty()) {
                            YandexHudService.cachedManeuverDir = dir;
                        }
                    }
                }
            }
            if (event.getContentDescription() != null) {
                String dir = YandexHudService.detectDirection(event.getContentDescription().toString());
                if (!dir.isEmpty()) {
                    YandexHudService.cachedManeuverDir = dir;
                }
            }

            long now = System.currentTimeMillis();
            if (now - lastScanTime > 450) {
                lastScanTime = now;
                scanActiveWindowsForYandex();
            }
        }
    }

    private void scanActiveWindowsForYandex() {
        List<AccessibilityNodeInfo> yandexRoots = new ArrayList<>();

        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo win : windows) {
                    AccessibilityNodeInfo root = win.getRoot();
                    if (root != null && root.getPackageName() != null) {
                        String p = root.getPackageName().toString().toLowerCase();
                        if (p.contains("yandex")) {
                            yandexRoots.add(root);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        if (yandexRoots.isEmpty()) {
            try {
                AccessibilityNodeInfo active = getRootInActiveWindow();
                if (active != null && active.getPackageName() != null) {
                    String p = active.getPackageName().toString().toLowerCase();
                    if (p.contains("yandex")) {
                        yandexRoots.add(active);
                    }
                }
            } catch (Exception ignored) {}
        }

        if (yandexRoots.isEmpty()) {
            isYandexWindowActive = false;
            return;
        }

        isYandexWindowActive = true;

        DisplayMetrics dm = getResources().getDisplayMetrics();
        Rect screenBounds = new Rect(0, 0, dm.widthPixels, dm.heightPixels);

        List<String> topLeftTokens = new ArrayList<>();
        List<String> detectedDirs = new ArrayList<>();
        List<TopRightNumber> topRightNumbers = new ArrayList<>();

        for (AccessibilityNodeInfo root : yandexRoots) {
            Rect wb = new Rect();
            root.getBoundsInScreen(wb);
            if (wb.width() <= 100 || wb.height() <= 100) {
                wb = screenBounds;
            }
            traverseNodeTree(root, wb, topLeftTokens, detectedDirs, topRightNumbers);
        }

        // 1. Знак ограничения скорости из правого верхнего угла открытой карты [ 0 | (20) ]
        int speedLimit = resolveSpeedLimit(topRightNumbers);
        long now = System.currentTimeMillis();
        if (speedLimit >= 20 && speedLimit <= 130 && (speedLimit % 10 == 0)) {
            if (speedLimit != lastSentSpeedLimit || (now - lastSpeedLimitSendTime > 10000)) {
                lastSentSpeedLimit = speedLimit;
                lastSpeedLimitSendTime = now;
                if (YandexHudService.instance != null) {
                    YandexHudService.instance.sendToEsp32("$Y,0 Ограничение " + speedLimit + ";\n");
                }
            }
        }

        // 2. Склеиваем раздельные TextView левого верхнего угла ("50" + "м" -> "50 м")
        StringBuilder joinedTopLeft = new StringBuilder();
        for (String tok : topLeftTokens) {
            if (joinedTopLeft.length() > 0) joinedTopLeft.append(" ");
            joinedTopLeft.append(tok);
        }
        String joinedStr = joinedTopLeft.toString().trim();
        lastScreenDebug = joinedStr.isEmpty() ? "Карта открыта (ожидание плашки)" : joinedStr;

        Matcher mDist = YandexHudService.DIST_PATTERN.matcher(joinedStr);
        if (mDist.find()) {
            String dist = mDist.group(1).replace(',', '.');
            String dir = "";
            if (!detectedDirs.isEmpty()) {
                dir = detectedDirs.get(0);
                YandexHudService.cachedManeuverDir = dir;
            } else if (!YandexHudService.cachedManeuverDir.isEmpty()) {
                dir = YandexHudService.cachedManeuverDir;
            } else {
                dir = "Прямо";
            }

            String rem = joinedStr.replace(mDist.group(1), "").trim();
            String street = YandexHudService.extractPureStreetName(rem);
            if (street.isEmpty() && !YandexHudService.cachedStreetName.isEmpty()) {
                street = YandexHudService.cachedStreetName;
            }

            String packet = YandexHudService.parseYandexNotification(dist, street.isEmpty() ? dir : street, "", dir);
            if (packet != null && YandexHudService.instance != null) {
                YandexHudService.instance.sendDeduplicated(packet);
            }
        }
    }

    private void traverseNodeTree(
            AccessibilityNodeInfo node,
            Rect winBounds,
            List<String> topLeftTokens,
            List<String> detectedDirs,
            List<TopRightNumber> topRightNumbers) {

        if (node == null) return;

        Rect r = new Rect();
        node.getBoundsInScreen(r);

        int relX = r.centerX() - winBounds.left;
        int relY = r.centerY() - winBounds.top;
        int winW = winBounds.width();
        int winH = winBounds.height();

        CharSequence cdCs = node.getContentDescription();
        String cd = cdCs != null ? YandexHudService.cleanString(cdCs.toString()) : "";
        String viewId = node.getViewIdResourceName() != null ? node.getViewIdResourceName().toLowerCase() : "";

        // В левой верхней части ищем подсказки направления в contentDescription и ID иконки
        if (relX >= 0 && relX < winW * 0.55f && relY >= 0 && relY < winH * 0.52f) {
            if (!cd.isEmpty()) {
                String d = YandexHudService.detectDirection(cd);
                if (!d.isEmpty() && !detectedDirs.contains(d)) {
                    detectedDirs.add(d);
                }
                Matcher mCd = YandexHudService.DIST_PATTERN.matcher(cd);
                if (mCd.find() && !topLeftTokens.contains(cd)) {
                    topLeftTokens.add(cd);
                }
            }
            String idDir = detectDirFromViewId(viewId);
            if (!idDir.isEmpty() && !detectedDirs.contains(idDir)) {
                detectedDirs.add(idDir);
            }
        }

        CharSequence txtCs = node.getText();
        if (txtCs != null) {
            String txt = YandexHudService.cleanString(txtCs.toString());
            if (!txt.isEmpty()) {
                // Левая верхняя зона (синяя плашка маневра: "50", "м", "100", "м", улица)
                // Отсекаем нижнюю панель времени/маршрута (relY < winH * 0.48f)
                if (relX >= 0 && relX < winW * 0.52f && relY >= 0 && relY < winH * 0.48f) {
                    String dirFromTxt = YandexHudService.detectDirection(txt);
                    if (!dirFromTxt.isEmpty() && !detectedDirs.contains(dirFromTxt)) {
                        detectedDirs.add(dirFromTxt);
                    }
                    if (!isIgnoredUiText(txt) && !topLeftTokens.contains(txt)) {
                        topLeftTokens.add(txt);
                    }
                }

                // Правая верхняя зона: плашка [ Скорость | (Ограничение) ]
                if (relX > winW * 0.55f && relY >= 0 && relY < winH * 0.42f) {
                    if (txt.matches("^\\d{1,3}$")) {
                        try {
                            int val = Integer.parseInt(txt);
                            boolean hint = viewId.contains("limit") || viewId.contains("restrict") || cd.toLowerCase().contains("огранич");
                            topRightNumbers.add(new TopRightNumber(val, r.centerX(), hint));
                        } catch (Exception ignored) {}
                    }
                }
            }
        }

        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            traverseNodeTree(node.getChild(i), winBounds, topLeftTokens, detectedDirs, topRightNumbers);
        }
    }

    private int resolveSpeedLimit(List<TopRightNumber> list) {
        if (list.isEmpty()) return 0;
        for (TopRightNumber item : list) {
            if (item.hasLimitHint && item.value >= 20 && item.value <= 130 && (item.value % 10 == 0)) {
                return item.value;
            }
        }
        if (list.size() >= 2) {
            TopRightNumber rightmost = list.get(0);
            for (TopRightNumber item : list) {
                if (item.centerX > rightmost.centerX) {
                    rightmost = item;
                }
            }
            if (rightmost.value >= 20 && rightmost.value <= 130 && (rightmost.value % 10 == 0)) {
                return rightmost.value;
            }
        }
        return 0;
    }

    private String detectDirFromViewId(String viewId) {
        if (viewId == null || viewId.isEmpty()) return "";
        if (viewId.contains("finish") || viewId.contains("arrival")) return "Почти на месте";
        if (viewId.contains("uturn") || viewId.contains("u_turn") || viewId.contains("reverse")) return "Разворот";
        if (viewId.contains("roundabout") || viewId.contains("circle")) return "Кольцо";
        if (viewId.contains("hard_left") || viewId.contains("sharp_left")) return "Круто налево";
        if (viewId.contains("hard_right") || viewId.contains("sharp_right")) return "Круто направо";
        if (viewId.contains("slight_left") || viewId.contains("keep_left")) return "Левее";
        if (viewId.contains("slight_right") || viewId.contains("keep_right")) return "Правее";
        if (viewId.contains("left")) return "Налево";
        if (viewId.contains("right")) return "Направо";
        return "";
    }

    private boolean isIgnoredUiText(String s) {
        String low = s.toLowerCase();
        // ВАЖНО: Разрешаем одиночные буквы "м", "км" и числа "50", "100", чтобы склеивать "50" + "м" -> "50 м"!
        if (low.equals("м") || low.equals("км") || low.equals("m") || low.equals("km")) return false;
        if (low.matches("^\\d+[.,]?\\d*$")) return false;
        if (low.length() < 2) return true;
        if (low.matches(".*\\d{1,2}:\\d{2}.*")) return true;
        return low.contains("поиск") ||
               low.contains("алиса") ||
               low.contains("поехали") ||
               low.contains("по пути") ||
               low.contains("завершить") ||
               low.contains("отмена") ||
               low.contains("настроить") ||
               low.contains("местополож") ||
               low.contains("откуда") ||
               low.contains("куда") ||
               low.contains("заехать") ||
               low.contains("мин") ||
               low.contains("быстрее");
    }
}