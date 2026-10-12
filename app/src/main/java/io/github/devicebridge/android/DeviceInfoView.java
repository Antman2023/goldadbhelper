package io.github.devicebridge.android;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import io.github.devicebridge.core.DeviceParsers;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A read-only device snapshot. All displayed values originate in the supplied shell results. */
public final class DeviceInfoView extends ScrollView {
    private static final int INK = 0xff101010, MUTED = 0xff757575, BLUE = 0xff1684ec, PINK = 0xffff4f81;
    private static final String MISSING = "未提供";
    private final Map<String, String> snapshots;
    private final Runnable refresh;
    private final LinearLayout content;
    private final TextView pullHint;
    private float startX, startY;
    private boolean startedAtTop, pulling;
    private long lastRefresh = -1200;

    /** Keys: properties, size, density, cpu, memory, storage, battery, network, wifi, display. */
    public DeviceInfoView(Context context, Map<String, String> snapshots, Runnable refresh) {
        super(context);
        this.snapshots = snapshots == null ? Collections.emptyMap() : new LinkedHashMap<>(snapshots);
        this.refresh = refresh;
        setFillViewport(true);
        setClipToPadding(false);
        setBackgroundColor(0xfff7f7f7);
        content = Ui.col(context);
        content.setPadding(0, 0, 0, dp(24));
        addView(content, new ScrollView.LayoutParams(-1, -2));
        pullHint = text("下拉刷新", 13, MUTED);
        pullHint.setPadding(dp(18), dp(10), dp(18), dp(10));
        pullHint.setGravity(Gravity.CENTER);
        pullHint.setVisibility(GONE);
        content.addView(pullHint, new LinearLayout.LayoutParams(-1, -2));
        build();
    }

    private int dp(float value) { return Ui.dp(getContext(), value); }
    private String raw(String key) { String value = snapshots.get(key); return value == null ? "" : value; }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(getContext());
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        return view;
    }
    private static String shown(String value) { return value == null || value.trim().isEmpty() ? MISSING : value.trim(); }
    private static String first(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return null;
    }
    private static String number(Number value) { return value == null ? null : value.toString(); }

    private void build() {
        DeviceParsers.DeviceInfo device = DeviceParsers.deviceInfo(raw("properties"));
        DeviceParsers.CpuInfo cpu = DeviceParsers.cpu(raw("cpu"));
        DeviceParsers.MemoryInfo memory = DeviceParsers.memory(raw("memory"));
        DeviceParsers.DisplayInfo display = DeviceParsers.display(raw("size"), raw("density"));
        DeviceParsers.BatteryInfo battery = DeviceParsers.battery(raw("battery"));
        List<DeviceParsers.StorageInfo> disks = DeviceParsers.storage(raw("storage"));
        DeviceParsers.StorageInfo storage = primaryStorage(disks);
        Map<String, String> props = device.properties;
        String cpuName = first(cpu.hardware, props.get("ro.soc.model"), cpu.model, props.get("ro.hardware"));
        String model = first(device.model, props.get("ro.product.name"));
        String manufacturer = device.manufacturer;
        String name = manufacturer == null || manufacturer.trim().isEmpty() ? shown(model)
                : model == null || model.trim().isEmpty() ? manufacturer : manufacturer + " " + model;

        LinearLayout card = Ui.col(getContext());
        card.setPadding(0, dp(8), 0, dp(8));
        card.setBackground(Ui.rounded(0xffffffff, dp(6)));
        Compatibility.elevation(card,dp(5));
        LinearLayout.LayoutParams cardLayout = new LinearLayout.LayoutParams(-1, -2);
        cardLayout.setMargins(dp(13), dp(15), dp(13), dp(16));
        content.addView(card, cardLayout);
        summary(card, 0, name, null, null);
        summary(card, 1, device.androidVersion == null ? MISSING : "Android " + device.androidVersion, "版本", null);
        summary(card, 2, dimensions(display.effectiveWidth(), display.effectiveHeight()), "分辨率", null);
        summary(card, 3, shown(cpuName), "CPU", null);
        summary(card, 4, bytes(memory.totalBytes), "运行内存", null);
        summary(card, 5, storage == null ? MISSING : storage.approximate
                        ? "总计约 " + storage.reportedTotal + "/空闲约 " + storage.reportedAvailable
                        : "总计" + bytes(storage.totalBytes) + "/可用" + bytes(storage.availableBytes),
                "存储空间", storage == null || storage.approximate || storage.totalBytes <= 0 ? null : (double) storage.usedBytes / storage.totalBytes);

        section("系统");
        detail("产品代号", device.product);
        detail("制造商", device.manufacturer);
        detail("设备型号", device.model);
        detail("Android 版本", device.androidVersion);
        detail("API 级别", number(device.apiLevel));
        detail("安全补丁", device.securityPatch);
        detail("系统版本", props.get("ro.build.display.id"));
        detail("主板", props.get("ro.product.board"));
        detail("引导程序", props.get("ro.bootloader"));
        detail("序列号", device.serial);
        detail("构建指纹", device.fingerprint);

        section("CPU");
        detail("处理器", cpuName);
        detail("硬件平台", first(cpu.hardware, props.get("ro.hardware")));
        detail("SoC 厂商", props.get("ro.soc.manufacturer"));
        detail("逻辑核心数", number(cpu.logicalProcessorCount));
        detail("CPU 架构", first(cpu.model, props.get("ro.product.cpu.abi")));
        detail("支持的指令集", device.abis);
        detail("处理器特性", cpu.features);

        section("显示");
        detail("当前分辨率", dimensions(display.effectiveWidth(), display.effectiveHeight()));
        detail("物理分辨率", dimensions(display.physicalWidth, display.physicalHeight));
        detail("覆盖分辨率", display.overrideWidth == null ? "未设置或未提供" : dimensions(display.overrideWidth, display.overrideHeight));
        detail("当前逻辑密度", density(display.effectiveDensity()));
        detail("默认逻辑密度", density(display.physicalDensity));
        detail("覆盖逻辑密度", display.overrideDensity == null ? "未设置或未提供" : density(display.overrideDensity));
        double[] physicalDpi = reportedDpi(raw("display"));
        detail("设备报告的像素密度", physicalDpi == null ? "未提供；逻辑密度不能作为物理 PPI"
                : decimal(physicalDpi[0], 1) + " × " + decimal(physicalDpi[1], 1) + " PPI");
        if (physicalDpi != null && display.physicalWidth != null && display.physicalHeight != null) {
            double diagonal = Math.hypot(display.physicalWidth / physicalDpi[0], display.physicalHeight / physicalDpi[1]);
            detail("屏幕对角线（估算）", decimal(diagonal, 2) + " 英寸 · 依设备报告值计算");
        }
        detail("刷新率", refreshRate(raw("display")));

        section("运行内存");
        detail("总内存", bytes(memory.totalBytes));
        detail("可用内存", bytes(memory.availableBytes));
        detail("已用内存", bytes(memory.usedBytes));
        detail("完全空闲内存", bytes(memory.freeBytes));
        detail("内存统计说明", "可用内存取自 MemAvailable；完全空闲内存取自 MemFree，二者含义不同。");

        section("存储空间");
        if (storage == null) detail("主要数据分区", "未提供；未将系统或临时分区当作用户空间");
        else {
            detail("统计路径", storage.mountPoint);
            detail("文件系统", storage.filesystem);
            detail("总空间", storage.approximate ? "约 " + storage.reportedTotal : bytes(storage.totalBytes));
            detail("已用空间", storage.approximate ? "约 " + storage.reportedUsed : bytes(storage.usedBytes));
            detail(storage.availableIncludesReserved ? "空闲空间" : "可用空间", storage.approximate ? "约 " + storage.reportedAvailable : bytes(storage.availableBytes));
            detail("已用比例", storage.percentUsed == null ? null : storage.percentUsed + "%");
            if (storage.approximate) detail("统计说明", "旧版 df 已舍入；空闲空间可能包括保留块，不等同当前用户可写空间。");
        }
        for (DeviceParsers.StorageInfo disk : disks) {
            if (disk == storage) continue;
            String freeLabel = disk.availableIncludesReserved ? "空闲" : "可用";
            detail("分区 " + disk.mountPoint, disk.approximate
                    ? "总计约 " + disk.reportedTotal + " · " + freeLabel + "约 " + disk.reportedAvailable
                    : "总计 " + bytes(disk.totalBytes) + " · " + freeLabel + " " + bytes(disk.availableBytes));
        }

        section("网络");
        List<DeviceParsers.NetworkAddress> addresses = DeviceParsers.network(raw("network"));
        boolean hasAddress = false;
        for (DeviceParsers.NetworkAddress address : addresses) {
            if ("lo".equals(address.interfaceName)) continue;
            hasAddress = true;
            detail(address.interfaceName + ("inet6".equals(address.family) ? " · IPv6" : " · IPv4"),
                    address.address + "/" + address.prefixLength);
        }
        if (!hasAddress) detail("网络地址", "未提供非回环接口地址");
        detail("运营商", props.get("gsm.operator.alpha"));
        detail("移动网络类型", props.get("gsm.network.type"));
        String wifi = raw("wifi");
        detail("Wi-Fi 状态", wifiStatus(wifi));
        detail("Wi-Fi 名称", match(wifi, "(?im)\\bSSID:\\s*(.*?)(?=,\\s*(?:BSSID|MAC|Supplicant|Wi-?Fi|RSSI|Link|Frequency|Net|Metered|Score)\\b|$)"));
        String rssi = match(wifi, "(?i)\\bRSSI:\\s*(-?\\d+)");
        detail("Wi-Fi 信号", rssi == null ? null : rssi + " dBm");
        String linkSpeed = match(wifi, "(?i)\\bLink speed:\\s*(\\d+)\\s*Mbps");
        detail("Wi-Fi 连接速率", linkSpeed == null ? null : linkSpeed + " Mbps");
        String frequency = match(wifi, "(?i)\\bFrequency:\\s*(\\d+)");
        detail("Wi-Fi 频率", frequency == null ? null : frequency + " MHz");

        section("电池");
        detail("电池电量", battery.percentage == null ? null : decimal(battery.percentage, 0) + "%");
        detail("充电状态", batteryStatus(battery.status));
        detail("健康状况", batteryHealth(battery.health));
        detail("电池温度", battery.temperatureCelsius == null ? null : decimal(battery.temperatureCelsius, 1) + " °C");
        detail("电池电压", battery.voltageMillivolts == null ? null : decimal(battery.voltageMillivolts / 1000.0, 3) + " V");
        detail("电池技术", battery.technology);
        detail("电池存在", yesNo(battery.present));
        detail("交流电供电", yesNo(battery.acPowered));
        detail("USB 供电", yesNo(battery.usbPowered));
        detail("无线供电", yesNo(battery.wirelessPowered));

        TextView explanation = text("未提供表示设备未报告该项、系统限制或读取未成功。下拉可刷新当前设备的信息。", 12, MUTED);
        explanation.setPadding(dp(18), dp(20), dp(18), dp(8));
        content.addView(explanation, new LinearLayout.LayoutParams(-1, -2));
        Button update = Ui.button(getContext(), "刷新设备信息", this::requestRefresh);
        update.setEnabled(refresh != null);
        LinearLayout.LayoutParams button = new LinearLayout.LayoutParams(-1, -2);
        button.setMargins(dp(18), 0, dp(18), 0);
        content.addView(update, button);
        Button raw = Ui.button(getContext(), "查看原始读取结果", this::showSnapshots);
        LinearLayout.LayoutParams rawLayout = new LinearLayout.LayoutParams(-1, -2);
        rawLayout.setMargins(dp(18), 0, dp(18), 0);
        content.addView(raw, rawLayout);
    }

    private void summary(LinearLayout card, int icon, String value, String label, Double usedFraction) {
        LinearLayout row = Ui.row(getContext());
        row.setMinimumHeight(dp(icon == 5 ? 80 : 60));
        row.setPadding(dp(16), dp(8), dp(22), dp(8));
        SummaryIcon symbol = new SummaryIcon(getContext(), icon);
        symbol.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(symbol, new LinearLayout.LayoutParams(dp(44), dp(36)));
        LinearLayout values = Ui.col(getContext());
        values.setGravity(Gravity.CENTER_VERTICAL);
        TextView main = text(shown(value), icon == 5 ? 16 : 18, INK);
        main.setTextIsSelectable(true);
        values.addView(main, new LinearLayout.LayoutParams(-1, -2));
        if (icon == 5 && usedFraction != null) {
            UsageBar bar = new UsageBar(getContext(), usedFraction);
            bar.setContentDescription("存储空间已用 " + decimal(100 * Math.max(0, Math.min(1, usedFraction)), 0) + "%");
            LinearLayout.LayoutParams usage = new LinearLayout.LayoutParams(-1, dp(4));
            usage.topMargin = dp(6);
            values.addView(bar, usage);
        }
        LinearLayout.LayoutParams valueLayout = new LinearLayout.LayoutParams(0, -2, 1);
        valueLayout.leftMargin = dp(10);
        row.addView(values, valueLayout);
        if (label != null) {
            TextView caption = text(label, 14, MUTED);
            caption.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams captionLayout = new LinearLayout.LayoutParams(-2, -2);
            captionLayout.leftMargin = dp(8);
            row.addView(caption, captionLayout);
        }
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void section(String title) {
        TextView heading = text(title, 14, BLUE);
        heading.setPadding(dp(18), 0, dp(18), 0);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(heading, new LinearLayout.LayoutParams(-1, dp(56)));
    }

    private void detail(String title, String value) {
        LinearLayout row = Ui.row(getContext());
        row.setGravity(Gravity.TOP);
        row.setMinimumHeight(dp(48));
        row.setPadding(dp(18), dp(10), dp(18), dp(10));
        TextView key = text(title, 16, INK);
        LinearLayout.LayoutParams label = new LinearLayout.LayoutParams(-2, -2);
        label.rightMargin = dp(8);
        key.setMaxWidth(dp(150));
        row.addView(key, label);
        TextView data = text(shown(value), 16, MUTED);
        data.setGravity(Gravity.END);
        data.setTextIsSelectable(true);
        row.addView(data, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private static DeviceParsers.StorageInfo primaryStorage(List<DeviceParsers.StorageInfo> disks) {
        for (DeviceParsers.StorageInfo disk : disks) if ("/data".equals(disk.mountPoint)) return disk;
        for (DeviceParsers.StorageInfo disk : disks) if ("/storage/emulated/0".equals(disk.mountPoint)
                || "/storage/emulated".equals(disk.mountPoint) || "/sdcard".equals(disk.mountPoint)) return disk;
        return null;
    }

    private static String dimensions(Integer width, Integer height) {
        return width == null || height == null ? MISSING : width + " x " + height;
    }
    private static String density(Integer density) { return density == null ? null : density + " dpi（逻辑）"; }
    private static String bytes(Long bytes) {
        if (bytes == null || bytes < 0) return MISSING;
        if (bytes >= 1073741824L) return decimal(bytes / 1073741824.0, 2) + " GB";
        if (bytes >= 1048576L) return decimal(bytes / 1048576.0, 2) + " MB";
        if (bytes >= 1024L) return decimal(bytes / 1024.0, 2) + " KB";
        return bytes + " B";
    }
    private static String decimal(double value, int digits) { return String.format(Locale.US, "%." + digits + "f", value); }
    private static String yesNo(Boolean value) { return value == null ? null : value ? "是" : "否"; }
    private static String match(String raw, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(raw == null ? "" : raw);
        return matcher.find() ? matcher.group(1).trim() : null;
    }
    private static String wifiStatus(String wifi) {
        if (Pattern.compile("(?im)^\\s*Wi-?Fi is disabled\\b").matcher(wifi).find()) return "已关闭";
        if (Pattern.compile("(?im)^\\s*Wi-?Fi is connected\\b").matcher(wifi).find()) return "已连接";
        if (Pattern.compile("(?im)^\\s*Wi-?Fi is not connected\\b").matcher(wifi).find()) return "未连接";
        if (Pattern.compile("(?im)^\\s*Wi-?Fi is enabled\\b").matcher(wifi).find()) return "已开启";
        return null;
    }
    private static String batteryStatus(Integer status) {
        if (status == null) return null;
        switch (status) { case 1: return "未知"; case 2: return "充电中"; case 3: return "放电中";
            case 4: return "未充电"; case 5: return "已充满"; default: return "设备报告值 " + status; }
    }
    private static String batteryHealth(Integer health) {
        if (health == null) return null;
        switch (health) { case 1: return "未知"; case 2: return "良好"; case 3: return "过热";
            case 4: return "失效"; case 5: return "电压过高"; case 6: return "未指明故障";
            case 7: return "过冷"; default: return "设备报告值 " + health; }
    }

    /** Only use the explicitly identified default/internal display, never a random external display. */
    private static String defaultDisplayLine(String raw) {
        String candidate = null;
        for (String line : raw.split("\\r?\\n")) {
            if (!line.contains("DisplayDeviceInfo")) continue;
            if (line.contains("FLAG_DEFAULT_DISPLAY")) return line;
            if (line.contains("uniqueId=\"local:0\"") || line.contains("type INTERNAL")) {
                if (candidate != null) return null;
                candidate = line;
            }
        }
        return candidate;
    }
    private static double[] reportedDpi(String raw) {
        String line = defaultDisplayLine(raw);
        if (line == null) return null;
        Matcher matcher = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*x\\s*([0-9]+(?:\\.[0-9]+)?)\\s*dpi\\b").matcher(line);
        if (!matcher.find()) return null;
        try {
            double x = Double.parseDouble(matcher.group(1)), y = Double.parseDouble(matcher.group(2));
            return x > 0 && y > 0 && !Double.isInfinite(x) && !Double.isInfinite(y) ? new double[]{x, y} : null;
        } catch (NumberFormatException ignored) { return null; }
    }
    private static String refreshRate(String raw) {
        String line = defaultDisplayLine(raw);
        if (line == null) return null;
        String currentMode = match(line, "\\bmodeId[= ]+(\\d+)");
        if (currentMode != null) {
            String rate = match(line, "\\bid[= ]+" + Pattern.quote(currentMode) + "\\b[^}]*?\\bfps[= ]+([0-9]+(?:\\.[0-9]+)?)");
            if (rate != null) return rate + " Hz（设备报告）";
        }
        String rate = match(line, "\\brefreshRate[= ]+([0-9]+(?:\\.[0-9]+)?)");
        return rate == null ? null : rate + " Hz（设备报告）";
    }

    private void showSnapshots() {
        final String[] keys = {"properties", "size", "density", "cpu", "memory", "storage", "battery", "network", "wifi", "display"};
        final String[] names = {"系统属性", "屏幕尺寸", "逻辑密度", "CPU", "内存", "存储", "电池", "网络地址", "Wi-Fi", "显示设备"};
        new AlertDialog.Builder(getContext()).setTitle("原始读取结果").setItems(names, (dialog, which) -> {
            TextView data = Ui.text(getContext(), shown(raw(keys[which])), 12);
            data.setTextIsSelectable(true);
            ScrollView scroll = new ScrollView(getContext()); scroll.addView(data);
            new AlertDialog.Builder(getContext()).setTitle(names[which]).setView(scroll).setPositiveButton("关闭", null).show();
        }).setNegativeButton("关闭", null).show();
    }

    private void requestRefresh() {
        long now = SystemClock.elapsedRealtime();
        if (refresh != null && now - lastRefresh >= 1200) { lastRefresh = now; refresh.run(); }
    }

    /** A single-finger downward gesture is claimed only when it began at the top of this ScrollView. */
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (refresh == null) return super.dispatchTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startX = event.getX(); startY = event.getY(); startedAtTop = getScrollY() == 0; pulling = false;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                startedAtTop = false;
                if (pulling) { pulling = false; pullHint.setVisibility(GONE); return true; }
                break;
            case MotionEvent.ACTION_MOVE:
                float delta = event.getY() - startY;
                if (!pulling && startedAtTop && event.getPointerCount() == 1 && delta > dp(24)
                        && delta > Math.abs(event.getX() - startX) * 1.5f && getScrollY() == 0) {
                    pulling = true;
                    MotionEvent cancel = MotionEvent.obtain(event); cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel); cancel.recycle();
                    pullHint.setVisibility(VISIBLE);
                }
                if (pulling) {
                    pullHint.setText(delta >= dp(72) ? "释放刷新设备信息" : "继续下拉以刷新");
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (pulling) {
                    boolean refreshNow = event.getActionMasked() == MotionEvent.ACTION_UP && event.getY() - startY >= dp(72);
                    pulling = false; startedAtTop = false; pullHint.setVisibility(GONE);
                    if (refreshNow) requestRefresh();
                    return true;
                }
                startedAtTop = false;
                break;
            default: break;
        }
        return super.dispatchTouchEvent(event);
    }

    private static final class UsageBar extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final double used;
        UsageBar(Context context, double used) { super(context); this.used = Math.max(0, Math.min(1, used)); }
        @Override protected void onDraw(Canvas canvas) {
            paint.setColor(0xffd9d9d9); canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            paint.setColor(PINK); canvas.drawRect(0, 0, (float) (getWidth() * used), getHeight(), paint);
        }
    }

    private static final class SummaryIcon extends View {
        private static final int[] COLORS = {0xff258aeb, 0xff00a394, 0xff25bfd0, 0xff898516, 0xff0082c8, 0xff9025b8};
        private final int index;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        SummaryIcon(Context context, int index) { super(context); this.index = index; }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save(); canvas.translate(getWidth() / 2f, getHeight() / 2f);
            float density = getResources().getDisplayMetrics().density; canvas.scale(density, density);
            paint.setColor(COLORS[index]); paint.setStrokeWidth(2.7f); paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            switch (index) {
                case 0:
                    canvas.drawRoundRect(new RectF(-8, -13, 8, 13), 2.2f, 2.2f, paint);
                    canvas.drawLine(-7, 9, 7, 9, paint); canvas.drawLine(-2, 11, 2, 11, paint); break;
                case 1:
                    canvas.drawRoundRect(new RectF(-10, -8, 10, 9), 4, 4, paint);
                    canvas.drawLine(-5, -11, -5, -8, paint); canvas.drawLine(5, -11, 5, -8, paint);
                    canvas.drawCircle(-4, -1, 1, paint); canvas.drawCircle(4, -1, 1, paint);
                    canvas.drawLine(-4, 5, 4, 5, paint); break;
                case 2:
                    for (int x : new int[]{-1, 1}) for (int y : new int[]{-1, 1}) {
                        canvas.drawLine(x * 11, y * 5, x * 11, y * 9, paint);
                        canvas.drawLine(x * 11, y * 9, x * 7, y * 9, paint);
                    }
                    paint.setStyle(Paint.Style.FILL); canvas.drawRoundRect(new RectF(-7, -5, 7, 5), 2, 2, paint); break;
                case 3:
                    canvas.drawRoundRect(new RectF(-8, -8, 8, 8), 1.5f, 1.5f, paint);
                    canvas.drawRect(-3, -3, 3, 3, paint);
                    for (int offset = -5; offset <= 5; offset += 5) {
                        canvas.drawLine(-11, offset, -8, offset, paint); canvas.drawLine(8, offset, 11, offset, paint);
                        canvas.drawLine(offset, -11, offset, -8, paint); canvas.drawLine(offset, 8, offset, 11, paint);
                    } break;
                case 4:
                    paint.setStrokeWidth(3.8f);
                    canvas.drawArc(new RectF(-11, -11, 11, 11), -70, 85, false, paint);
                    canvas.drawArc(new RectF(-11, -11, 11, 11), 45, 230, false, paint); break;
                case 5:
                    for (int y = -8; y <= 8; y += 8) {
                        canvas.drawRoundRect(new RectF(-12, y - 1.5f, 12, y + 1.5f), 1.5f, 1.5f, paint);
                        paint.setColor(0xffffffff); paint.setStrokeWidth(1.5f); canvas.drawPoint(-8, y, paint);
                        paint.setColor(COLORS[index]); paint.setStrokeWidth(2.7f);
                    } break;
                default: break;
            }
            canvas.restore();
        }
    }
}
