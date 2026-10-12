package io.github.devicebridge.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.function.IntConsumer;

/** Shared, local-only remote settings. A session starts only through the supplied connect action. */
public final class RemoteSettingsView extends ScrollView {
    public static final String PREFERENCES = "jinjiachong_remote";
    public static final String CONNECT_IMMEDIATELY = "connect_immediately";
    private static final int BLUE = 0xff1976d2, PAPER = 0xfff8f8f8, MUTED = 0xff757575;
    private static final int[] BIT_RATES = {12000000,8000000,6000000,4000000,2000000,1000000};
    private static final String[] RESOLUTIONS = {"原始分辨率", "自动（最长边 1920）", "1080p（短边）", "720p（短边）", "540p（短边）", "360p（短边）"};
    private static final String[] RATES = {"12Mbps", "8Mbps", "6Mbps", "4Mbps", "2Mbps", "1Mbps"};
    private final Activity activity;
    private final SharedPreferences prefs;

    public RemoteSettingsView(Activity activity, Runnable onConnect) {
        super(activity); this.activity = activity; prefs = activity.getSharedPreferences(PREFERENCES, Activity.MODE_PRIVATE);
        setBackgroundColor(PAPER); setClipToPadding(false); setFillViewport(true);
        LinearLayout body = column(); body.setPadding(dp(12), dp(32), dp(12), dp(24)); addView(body);
        LinearLayout card = column(); card.setBackground(rounded(Color.WHITE, 6)); Compatibility.elevation(card,dp(4)); Compatibility.clipToOutline(card,true);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2); cardParams.setMargins(dp(2), 0, dp(2), 0);
        body.addView(card, cardParams);
        settingChoice(card, "分辨率", RESOLUTIONS, bounded(prefs.getInt("resolution", 0), 5),
                selected -> prefs.edit().putInt("resolution", selected).apply());
        int rateIndex = 1; for (int i = 0; i < BIT_RATES.length; i++) if (BIT_RATES[i] == prefs.getInt("bitrate", 8000000)) rateIndex = i;
        View rate = settingChoice(card, "码率", RATES, rateIndex, selected -> prefs.edit().putInt("bitrate", BIT_RATES[selected]).apply());
        settingChoice(card, "画面比例", new String[]{"保持原始比例", "拉伸填满"}, prefs.getBoolean("stretch", false) ? 1 : 0,
                selected -> prefs.edit().putBoolean("stretch", selected == 1).apply());
        settingChoice(card, "导航栏位置", new String[]{"底部", "悬浮", "不显示"}, bounded(prefs.getInt("navigation", 1), 2),
                selected -> prefs.edit().putInt("navigation", selected).apply());
        settingToggle(card, "全屏显示", prefs.getBoolean("fullscreen", false),
                (b, selected) -> prefs.edit().putBoolean("fullscreen", selected).apply());
        CheckBox off = settingToggle(card, "熄屏控制", prefs.getBoolean("screen_off", false) && !prefs.getBoolean("compatibility", false),
                (b, selected) -> prefs.edit().putBoolean("screen_off", selected).apply());
        View offRow = (View) off.getParent();
        Runnable modeUi = () -> {
            boolean compatible = prefs.getBoolean("compatibility", false);
            rate.setEnabled(!compatible); rate.setAlpha(compatible ? 0.45f : 1f);
            offRow.setEnabled(!compatible); off.setEnabled(!compatible); offRow.setAlpha(compatible ? 0.45f : 1f);
        };
        settingToggle(card, "兼容模式", prefs.getBoolean("compatibility", false), (b, selected) -> {
            SharedPreferences.Editor editor = prefs.edit().putBoolean("compatibility", selected);
            if (selected) editor.putBoolean("screen_off", false); editor.apply();
            if (selected) off.setChecked(false);
            modeUi.run();
        });
        modeUi.run();
        Button connect = new Button(activity); connect.setText("连接"); connect.setAllCaps(false); connect.setOnClickListener(v -> onConnect.run());
        connect.setTextColor(Color.WHITE); connect.setTextSize(14); connect.setPadding(0, 0, 0, 0); connect.setMinHeight(0); connect.setMinimumHeight(0);
        connect.setBackground(Compatibility.ripple(0x33ffffff, rounded(BLUE, 4), null)); Compatibility.elevation(connect,dp(2));
        LinearLayout.LayoutParams connectParams = new LinearLayout.LayoutParams(-1, dp(48)); connectParams.topMargin = dp(26);
        body.addView(connect, connectParams);
    }
    private static int bounded(int value, int max) { return Math.max(0, Math.min(max, value)); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout layout = new LinearLayout(activity); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable background = new GradientDrawable(); background.setColor(color); background.setCornerRadius(dp(radius)); return background;
    }
    private LinearLayout settingRow(LinearLayout card, String title) {
        // The measured reference rows are 69.5dp, including their dividers.
        if (card.getChildCount() > 0) {
            FrameLayout previous = (FrameLayout) card.getChildAt(card.getChildCount() - 1);
            View divider = new View(activity); divider.setBackgroundColor(0xffdedede);
            previous.addView(divider, new FrameLayout.LayoutParams(-1, dp(1), Gravity.BOTTOM));
        }
        FrameLayout frame = new FrameLayout(activity); card.addView(frame, new LinearLayout.LayoutParams(-1, dp(69.5f)));
        LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(16), 0, dp(8), 0);
        row.setBackground(Compatibility.ripple(0x18000000, null, rounded(Color.WHITE, 0)));
        TextView name = new TextView(activity); name.setText(title); name.setTextColor(Color.BLACK); name.setTextSize(16);
        row.addView(name, new LinearLayout.LayoutParams(-2, -2));
        frame.addView(row, new FrameLayout.LayoutParams(-1, -1)); return row;
    }
    private View settingChoice(LinearLayout card, String title, String[] choices, int selected, IntConsumer changed) {
        LinearLayout row = settingRow(card, title);
        TextView value = new TextView(activity); value.setText(choices[selected]); value.setTextSize(14); value.setTextColor(MUTED);
        value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL); value.setPadding(dp(16), 0, dp(16), 0);
        row.addView(value, new LinearLayout.LayoutParams(0, -1, 1));
        int[] selection = {selected}; row.setFocusable(true);
        row.setOnClickListener(v -> new AlertDialog.Builder(activity).setTitle(title).setSingleChoiceItems(choices, selection[0], (d, which) -> {
            selection[0] = which; value.setText(choices[which]); changed.accept(which); d.dismiss();
        }).setNegativeButton("取消", null).show()); return row;
    }
    private CheckBox settingToggle(LinearLayout card, String title, boolean selected, CompoundButton.OnCheckedChangeListener changed) {
        LinearLayout row = settingRow(card, title); row.addView(new View(activity), new LinearLayout.LayoutParams(0, 0, 1));
        CheckBox field = new CheckBox(activity); field.setContentDescription(title); field.setChecked(selected); field.setPadding(0, 0, 0, 0);
        Compatibility.buttonTint(field,new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{BLUE, MUTED}));
        row.addView(field, new LinearLayout.LayoutParams(dp(48), dp(48)));
        field.setOnCheckedChangeListener(changed); row.setOnClickListener(v -> field.setChecked(!field.isChecked())); return field;
    }
    public static void showAdvanced(Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFERENCES, Activity.MODE_PRIVATE);
        LinearLayout layout = new LinearLayout(activity); layout.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(16 * activity.getResources().getDisplayMetrics().density); layout.setPadding(padding, 0, padding, 0);
        CheckBox all = new CheckBox(activity); all.setText("渲染全部帧（可能增加延迟）"); all.setChecked(prefs.getBoolean("all_frames", false)); layout.addView(all);
        CheckBox awake = new CheckBox(activity); awake.setText("会话期间保持目标唤醒"); awake.setChecked(prefs.getBoolean("awake", false)); layout.addView(awake);
        TextView intervalLabel = new TextView(activity); intervalLabel.setText("兼容模式刷新间隔"); layout.addView(intervalLabel);
        Spinner interval = new Spinner(activity);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, new String[]{"0.3 秒", "0.7 秒", "1 秒", "2 秒"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); interval.setAdapter(adapter);
        int millis = prefs.getInt("interval", 700); interval.setSelection(millis <= 300 ? 0 : millis <= 700 ? 1 : millis <= 1000 ? 2 : 3); layout.addView(interval);
        new AlertDialog.Builder(activity).setTitle("高级设置").setView(layout).setNegativeButton("取消", null).setPositiveButton("保存", (d, w) ->
                prefs.edit().putBoolean("all_frames", all.isChecked()).putBoolean("awake", awake.isChecked())
                        .putInt("interval", new int[]{300,700,1000,2000}[interval.getSelectedItemPosition()]).apply()).show();
    }
    public static void showProvenance(Activity activity) {
        new AlertDialog.Builder(activity).setTitle("开源组件").setMessage("金甲虫ADB助手使用从 Genymobile 官方公开源码自行构建的 scrcpy 2.4 服务端（Apache-2.0），配套独立实现的 Android 客户端。\n\nhttps://github.com/Genymobile/scrcpy/tree/v2.4\n\n正常模式支持 H.264 视频和多点触控。兼容模式使用截图和 input 指令，仅支持单指操作。完整许可见应用“关于”。")
                .setPositiveButton("知道了", null).show();
    }
    public static void showHelp(Activity activity) {
        new AlertDialog.Builder(activity).setTitle("控制帮助").setMessage("正常模式使用官方开源 scrcpy 2.4 自行构建的服务端，配套独立 Android 客户端。\n\n正常模式：直接触摸画面，支持多指、长按、拖动。黑边不传递触摸。\n\n兼容模式：抬手后执行单击/滑动；长按通过原地 swipe 实现，不支持多指。\n\n部分品牌需在目标开发者选项开启模拟输入/USB 调试（安全设置）。安全窗口可能黑屏。目标旋转时旧坐标会失效，请抬手再操作。\n\n直接输入受目标键盘映射限制；中文请选择粘贴，该操作会覆盖目标剪贴板。音频不转发。\n\n返回本机后台会停止投屏，回到本页后重新建立会话。")
                .setNeutralButton("开源组件", (d, w) -> showProvenance(activity)).setPositiveButton("知道了", null).show();
    }
}
