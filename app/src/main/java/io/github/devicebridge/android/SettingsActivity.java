package io.github.devicebridge.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Grouped settings, implemented with platform widgets and independent geometric icons. */
public final class SettingsActivity extends Activity {
    private static final int INK = 0xff212121, MUTED = 0xff757575, PINK = 0xffff4f81;
    private static final String[] PAGES = {"设备信息", "实用工具", "远程控制", "文件管理", "应用管理", "进程管理", "运行命令"};
    private SharedPreferences prefs;
    private LinearLayout list;
    private ScrollView scroll;
    private String pickerId;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        pickerId = state == null ? null : state.getString("directory_request");
        show();
    }

    private int dp(float value) { return Ui.dp(this, value); }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private void show() {
        final int previousY = scroll == null ? 0 : scroll.getScrollY();
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(0xffffffff);
        Ui.inset(this, root);
        LinearLayout toolbar = Ui.row(this);
        toolbar.setMinimumHeight(dp(56));
        SettingsIcon back = new SettingsIcon("back", INK);
        back.setContentDescription("返回");
        back.setFocusable(true);
        back.setClickable(true);
        back.setBackground(ripple());
        back.setOnClickListener(view -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView title = text("设置", 20, INK);
        title.setPadding(dp(16), 0, dp(16), 0);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(56)));
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        list = Ui.col(this);
        list.setPadding(0, 0, 0, dp(16));
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        Ui.fill(root, scroll);
        setContentView(root);

        item("lock", "功能状态", "独立开发版 · 功能持续验证中", () -> Ui.message(this, "功能状态",
                "金甲虫ADB助手不使用原应用的高级版密钥或后台。独立功能没有原版数量限制。当前为开发验证版，仍有待验项目。"));
        divider();
        section("实用工具");
        item("save", "截图保存目录", prefs.getString("screenshot_tree", "每次保存时选择"),
                this::chooseScreenshotDirectory);
        divider();
        section("文件管理");
        hiddenFiles();
        divider();
        section("全局设置");
        int page = prefs.getInt("default_page", 0);
        if (page < 0 || page >= PAGES.length) page = 0;
        final int selectedPage = prefs.contains("default_page") ? page : -1;
        item("home", "连接设备后默认界面", selectedPage < 0 ? "未设置" : PAGES[page], () -> new AlertDialog.Builder(this)
                .setTitle("连接设备后默认界面")
                .setSingleChoiceItems(PAGES, selectedPage, (dialog, which) -> {
                    prefs.edit().putInt("default_page", which).apply();
                    dialog.dismiss();
                    show();
                }).setNegativeButton("取消", null).show());
        long cacheBytes = size(getCacheDir());
        item("refresh", "清除缓存", cacheBytes == 0 ? "缓存为空" : formatBytes(cacheBytes),
                () -> Ui.confirm(this, "清除缓存", "仅删除金甲虫ADB助手缓存，不删除ADB身份、连接历史、用户下载和截图。",
                        () -> { delete(getCacheDir()); show(); }));
        item("log", "崩溃日志", "查看最近一次异常退出的日志；不包含设备地址、配对码或密钥", this::crashLog);
        item("mail", "联系与反馈", "当前开发版本未配置外部反馈渠道", () -> Ui.message(this, "反馈",
                "请向提供此开发版本的人反馈，并附上复现步骤、Android版本及已脱敏的错误信息。应用不会自动上传日志。"));
        final String version = installedVersion();
        item("info", "关于金甲虫ADB助手", version + " · 独立设备管理工具", () -> new AlertDialog.Builder(this)
                .setTitle("金甲虫ADB助手 " + version)
                .setMessage("独立实现设备连接、信息、工具、远控、文件、应用、进程及终端。\n包名：" + getPackageName()
                        + "\n开发验证版；不能据此声称所有原版功能已通过对照验收。")
                .setNeutralButton("开源许可", (dialog, which) -> licenses())
                .setPositiveButton("确定", null).show());
        item("license", "开源许可", "scrcpy / Conscrypt / SPAKE2 · 可离线阅读", this::licenses);
        item("shield", "用户协议与隐私", "版本 1 · 本机处理", () -> Ui.message(this, "用户协议与隐私政策",
                "仅管理你拥有或获准管理的设备。ADB允许读取和修改设备；请确认每次危险操作的目标及后果。私钥存储在本机应用私有目录且不参与系统备份。连接历史及设置保存在本机；配对码不会写入日志或历史。本应用不包含广告、遥测、原应用付费服务或账号后台。网络连接由你选择的设备地址决定。卸载本应用会删除本机身份和设置，但目标设备上的ADB授权应在其设置中撤销。"));
        item("heart", "支持开发", "未配置收款主体", () -> Ui.message(this, "支持开发",
                "当前版本没有支付或捐赠渠道。不会使用原开发者的收款信息。"));
        scroll.post(() -> scroll.scrollTo(0, previousY));
    }

    private String installedVersion() {
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return version == null || version.isEmpty() ? "版本未提供" : version;
        } catch (android.content.pm.PackageManager.NameNotFoundException unavailable) {
            return "版本暂不可读";
        }
    }

    private android.graphics.drawable.Drawable ripple() {
        return Compatibility.ripple(0x16000000, new ColorDrawable(0xffffffff), null);
    }

    private void section(String title) {
        TextView heading = text(title, 14, PINK);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(72), dp(12), dp(16), 0);
        list.addView(heading, new LinearLayout.LayoutParams(-1, dp(56)));
    }

    private void divider() {
        View line = new View(this);
        line.setBackgroundColor(0xffdedede);
        list.addView(line, new LinearLayout.LayoutParams(-1, Math.max(1, dp(.75f))));
    }

    private LinearLayout row(String icon, String name, String summary) {
        LinearLayout row = Ui.row(this);
        row.setMinimumHeight(dp(72));
        row.setPadding(0, dp(16), dp(16), dp(16));
        row.setBackground(ripple());
        SettingsIcon symbol = new SettingsIcon(icon, icon.equals("lock") ? 0xff1976d2 : MUTED);
        symbol.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(symbol, new LinearLayout.LayoutParams(dp(56), dp(40)));
        LinearLayout words = Ui.col(this);
        words.setPadding(dp(16), 0, 0, 0);
        TextView title = text(name, 16, INK);
        words.addView(title, new LinearLayout.LayoutParams(-1, -2));
        if (summary != null && !summary.isEmpty()) {
            TextView sub = text(summary, 14, MUTED);
            words.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        }
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private void item(String icon, String name, String summary, Runnable action) {
        LinearLayout row = row(icon, name, summary);
        row.setFocusable(true);
        row.setClickable(true);
        row.setOnClickListener(view -> action.run());
        list.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void hiddenFiles() {
        LinearLayout row = row("eye", "默认显示隐藏文件", "显示以 . 开头的文件和文件夹");
        Switch toggle = new Switch(this);
        toggle.setContentDescription("默认显示隐藏文件");
        Compatibility.switchShowText(toggle,false);
        toggle.setChecked(prefs.getBoolean("show_hidden", false));
        toggle.setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean("show_hidden", checked).apply());
        LinearLayout.LayoutParams switchLayout = new LinearLayout.LayoutParams(dp(48), dp(48));
        switchLayout.leftMargin = dp(12);
        row.addView(toggle, switchLayout);
        row.setOnClickListener(view -> toggle.setChecked(!toggle.isChecked()));
        list.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void crashLog() {
        File file = new File(getFilesDir(), "crash.log");
        String log = "暂无崩溃日志";
        try (FileInputStream in = new FileInputStream(file)) {
            log = new String(MainActivity.readAll(in, 1024 * 1024), "UTF-8");
        } catch (IOException ignored) { }
        final String copied = log;
        TextView text = Ui.text(this, log, 14);
        text.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        new AlertDialog.Builder(this).setTitle("崩溃日志").setView(scroll)
                .setPositiveButton("复制", (dialog, which) -> ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                        .setPrimaryClip(ClipData.newPlainText("崩溃日志", copied)))
                .setNegativeButton("关闭", null).show();
    }

    private void licenses() {
        List<String> paths = new ArrayList<>();
        paths.add("scrcpy-LICENSE.txt");
        collectAssets("third_party", paths);
        new AlertDialog.Builder(this).setTitle("开源许可与版权声明")
                .setItems(paths.toArray(new String[0]), (dialog, which) -> {
                    try (InputStream in = getAssets().open(paths.get(which))) {
                        TextView text = Ui.text(this, new String(MainActivity.readAll(in, 512 * 1024), "UTF-8"), 12);
                        text.setTextIsSelectable(true);
                        ScrollView scroll = new ScrollView(this);
                        scroll.addView(text);
                        new AlertDialog.Builder(this).setTitle(paths.get(which)).setView(scroll)
                                .setPositiveButton("关闭", null).show();
                    } catch (IOException error) { Ui.message(this, "无法读取许可", error.getMessage()); }
                }).setNegativeButton("关闭", null).show();
    }

    private void collectAssets(String path, List<String> paths) {
        try {
            String[] children = getAssets().list(path);
            if (children == null || children.length == 0) { paths.add(path); return; }
            for (String child : children) collectAssets(path + "/" + child, paths);
        } catch (IOException ignored) { }
    }

    private long size(File file) {
        if (file.isFile()) return file.length();
        long total = 0;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) total += size(child);
        return total;
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    private void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        if (!file.equals(getCacheDir())) file.delete();
    }

    private void chooseScreenshotDirectory(){pickerId=java.util.UUID.randomUUID().toString();startActivityForResult(LocalFilesActivity.intent(this,LocalFilesActivity.MODE_DIRECTORY,pickerId),2);}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("directory_request",pickerId);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=2||data==null)return;if(data.hasExtra(LocalFilesActivity.EXTRA_REQUEST_ID)&&!java.util.Objects.equals(pickerId,data.getStringExtra(LocalFilesActivity.EXTRA_REQUEST_ID))){Ui.message(this,"目录选择已失效","请重新选择截图保存目录。");return;}if(result==LocalFilesActivity.RESULT_USE_SAF){if(Compatibility.supportsDocumentTree())startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),2);else Ui.message(this,"系统目录选择不可用","Android 4.4不支持目录树选择，请使用本机文件浏览器。");return;}if(result!=RESULT_OK||data.getData()==null)return;Uri uri=data.getData();try{if(!"file".equals(uri.getScheme()))getContentResolver().takePersistableUriPermission(uri,data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION));else if(!new java.io.File(uri.getPath()).isDirectory())throw new IllegalArgumentException("所选目录已不存在");java.util.ArrayList<String> tokens=data.getStringArrayListExtra("local_picker_source_tokens");String token=tokens!=null&&!tokens.isEmpty()?tokens.get(0):null;if("file".equals(uri.getScheme())&&token==null)token=Compatibility.directoryToken(new java.io.File(uri.getPath()));prefs.edit().putString("screenshot_tree",uri.toString()).putString("screenshot_tree_token",token).apply();show();}catch(Exception error){Ui.message(this,"目录不可用",error.getMessage());}}

    private final class SettingsIcon extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String kind;
        private final int color;
        SettingsIcon(String kind, int color) { super(SettingsActivity.this); this.kind = kind; this.color = color; }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save();
            canvas.translate(getWidth() / 2f, getHeight() / 2f);
            float density = getResources().getDisplayMetrics().density;
            canvas.scale(density, density);
            paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.8f);
            paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            Path path = new Path();
            switch (kind) {
                case "back":
                    canvas.drawLine(-7, 0, 7, 0, paint); canvas.drawLine(-7, 0, 0, -7, paint); canvas.drawLine(-7, 0, 0, 7, paint); break;
                case "lock":
                    canvas.drawRoundRect(new RectF(-7, -2, 7, 10), 1, 1, paint);
                    canvas.drawArc(new RectF(-4.5f, -9, 4.5f, 2), 180, 190, false, paint);
                    canvas.drawPoint(0, 4, paint); break;
                case "save":
                    path.moveTo(-8, -8); path.lineTo(5, -8); path.lineTo(8, -5); path.lineTo(8, 8); path.lineTo(-8, 8); path.close();
                    canvas.drawPath(path, paint); canvas.drawRect(-4, -7, 4, -2, paint); canvas.drawCircle(0, 3, 2, paint); break;
                case "eye":
                    path.moveTo(-10, 0); path.quadTo(0, -12, 10, 0); path.quadTo(0, 12, -10, 0); canvas.drawPath(path, paint); canvas.drawCircle(0, 0, 3.4f, paint); break;
                case "home":
                    path.moveTo(-9, -1); path.lineTo(0, -9); path.lineTo(9, -1); path.moveTo(-6, -3); path.lineTo(-6, 8);
                    path.lineTo(-1.5f, 8); path.lineTo(-1.5f, 2); path.lineTo(2, 2); path.lineTo(2, 8); path.lineTo(6, 8); path.lineTo(6, -3); canvas.drawPath(path, paint); break;
                case "refresh":
                    canvas.drawArc(new RectF(-7, -7, 7, 7), 205, 140, false, paint); canvas.drawArc(new RectF(-7, -7, 7, 7), 25, 140, false, paint);
                    path.moveTo(-10, 0); path.lineTo(-7, 4); path.lineTo(-4, 0); path.moveTo(10, 0); path.lineTo(7, -4); path.lineTo(4, 0); canvas.drawPath(path, paint); break;
                case "log":
                    canvas.drawRoundRect(new RectF(-8, -8, 8, 9), 1, 1, paint); canvas.drawRoundRect(new RectF(-3, -10, 3, -6), 1, 1, paint);
                    for (int y = -2; y <= 6; y += 4) canvas.drawLine(-4, y, 4, y, paint); break;
                case "mail":
                    canvas.drawRoundRect(new RectF(-9, -7, 9, 7), 1, 1, paint);
                    path.moveTo(-8, -6); path.lineTo(0, 0); path.lineTo(8, -6); canvas.drawPath(path, paint); break;
                case "license":
                    canvas.drawRect(-7, -9, 7, 9, paint); canvas.drawLine(-3, -4, 3, -4, paint); canvas.drawLine(-3, 0, 3, 0, paint); canvas.drawLine(-3, 4, 1, 4, paint); break;
                case "shield":
                    path.moveTo(0, -10); path.lineTo(8, -6); path.lineTo(7, 3); path.quadTo(5, 8, 0, 11); path.quadTo(-5, 8, -7, 3); path.lineTo(-8, -6); path.close(); canvas.drawPath(path, paint);
                    path.reset(); path.moveTo(-4, 0); path.lineTo(-1, 3); path.lineTo(4, -3); canvas.drawPath(path, paint); break;
                case "heart":
                    path.moveTo(0, 8); path.cubicTo(-17, -2, -6, -13, 0, -5); path.cubicTo(6, -13, 17, -2, 0, 8); canvas.drawPath(path, paint); break;
                default:
                    canvas.drawCircle(0, 0, 9, paint); canvas.drawLine(0, -1, 0, 5, paint); paint.setStyle(Paint.Style.FILL); canvas.drawCircle(0, -5, 1.2f, paint); break;
            }
            canvas.restore();
        }
    }
}
