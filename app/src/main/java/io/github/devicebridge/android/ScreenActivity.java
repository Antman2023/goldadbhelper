package io.github.devicebridge.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;

import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import io.github.devicebridge.core.AdbSession;
import io.github.devicebridge.core.ScrcpyProtocol;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Separate connection settings and remote session; ordinary Android controls, no copied artwork. */
public final class ScreenActivity extends Activity implements ScreenController.Listener, TextureView.SurfaceTextureListener {
    private static final int EXPORT_PNG = 4101;
    private static final int INK = 0xff242424, PAPER = 0xfff8f8f8, MUTED = 0xff757575;
    private ScreenController controller;
    private ScreenController.Options options = new ScreenController.Options();
    private SharedPreferences prefs;
    private String targetDescription = "";
    private FrameLayout root, picture;
    private TextureView texture;
    private ImageView screenshot;
    private TextView status;
    private LinearLayout bottomNav;
    private Surface surface;
    private SurfaceTexture activeTexture;
    private Bitmap shownBitmap;
    private int frameWidth, frameHeight, resolutionIndex, navigationIndex = 1, orientationIndex;
    private boolean fullScreen, viewing, resumed, exporting, firstFrame;
    private byte[] exportPng;
    private final Map<Integer, int[]> pointers = new LinkedHashMap<>();
    private int[] startPoint;
    private long downAt;
    private boolean compatibleGesture, multiplePointers;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        setTitle("远程控制");
        prefs = getSharedPreferences(RemoteSettingsView.PREFERENCES, MODE_PRIVATE);
        readSettings();
        AdbSession session = BridgeRuntime.session();
        if (session == null || session.state() != AdbSession.State.CONNECTED) {
            new AlertDialog.Builder(this).setTitle("未连接设备").setMessage("请先在连接页选择并授权目标设备。")
                    .setPositiveButton("返回", (d, w) -> finish()).setOnCancelListener(d -> finish()).show();
            return;
        }
        targetDescription = session.target();
        controller = new ScreenController(this, session, this);
        root = new FrameLayout(this); root.setBackgroundColor(PAPER);
        Compatibility.statusBarColor(getWindow(), PAPER);
        Compatibility.inset(root);
        setContentView(root);
        if (saved != null && saved.getBoolean("viewing")) showScreen();
        else {
            showSettings();
            if (saved == null && getIntent().getBooleanExtra(RemoteSettingsView.CONNECT_IMMEDIATELY, false)) connect();
        }
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView label(String text, int sp) {
        TextView v = new TextView(this); v.setText(text); v.setTextSize(sp); v.setTextColor(INK); v.setPadding(dp(12), dp(8), dp(12), dp(8)); return v;
    }
    private Button button(String text, Runnable action) {
        Button b = new Button(this); b.setText(text); b.setAllCaps(false); b.setOnClickListener(v -> action.run()); return b;
    }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private CheckBox check(String title, boolean selected) { CheckBox c = new CheckBox(this); c.setText(title); c.setChecked(selected); c.setPadding(dp(12), dp(8), dp(12), dp(8)); return c; }
    private void showSettings() {
        viewing = false;
        releasePointers(); frameWidth = frameHeight = 0;
        if (controller != null) controller.stop();
        disposeSurface(); root.removeAllViews(); applyFullscreen(false);
        LinearLayout outer = column(); root.addView(outer, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout toolbar = toolbar("远程控制", this::settingsNavigation, true);
        toolbar.addView(toolbarIcon(1, "帮助", this::help), new LinearLayout.LayoutParams(dp(48), dp(48)));
        View overflow = toolbarIcon(2, "更多选项", () -> {});
        overflow.setOnClickListener(v -> settingsMenu(v));
        toolbar.addView(overflow, new LinearLayout.LayoutParams(dp(40), dp(48)));
        outer.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(56)));

        outer.addView(new RemoteSettingsView(this, this::connect), new LinearLayout.LayoutParams(-1, 0, 1));
    }
    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable background = new GradientDrawable(); background.setColor(color); background.setCornerRadius(dp(radius)); return background;
    }
    private void connect() {
        if (viewing) return;
        readSettings();
        options.turnScreenOff = options.turnScreenOff && !options.compatibility; saveSettings();
        if (options.turnScreenOff || options.stayAwake) {
            new AlertDialog.Builder(this).setTitle("确认远控设置").setMessage("目标：" + targetDescription + "\n会话中将按设置改变目标物理屏幕/保持唤醒状态。正常退出时服务端尝试恢复；断线或系统限制可能影响恢复。")
                    .setNegativeButton("取消", null).setPositiveButton("连接", (d, w) -> { if (!viewing) showScreen(); }).show();
        } else showScreen();
    }
    private void settingsNavigation() {
        new AlertDialog.Builder(this).setTitle("当前设备：" + targetDescription)
                .setItems(new String[]{"返回设备管理", "高级设置", "控制帮助"}, (d, which) -> {
                    if (which == 0) finish(); else if (which == 1) advanced(); else help();
                }).setNegativeButton("取消", null).show();
    }
    private void settingsMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 0, 0, "高级设置"); menu.getMenu().add(0, 1, 1, "当前设备"); menu.getMenu().add(0, 2, 2, "开源组件");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 0) advanced();
            else if (item.getItemId() == 1) new AlertDialog.Builder(this).setTitle("当前设备").setMessage(targetDescription).setPositiveButton("确定", null).show();
            else provenance();
            return true;
        }); menu.show();
    }
    private void provenance() { RemoteSettingsView.showProvenance(this); }
    private LinearLayout toolbar(String title, Runnable back) { return toolbar(title, back, false); }
    private LinearLayout toolbar(String title, Runnable back, boolean navigation) {
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setBackgroundColor(PAPER);
        bar.addView(toolbarIcon(navigation ? 0 : 3, navigation ? "导航" : "返回", back), new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView name = new TextView(this); name.setText(title); name.setTextSize(20); name.setTextColor(INK); name.setGravity(Gravity.CENTER_VERTICAL); name.setPadding(dp(16), 0, 0, 0);
        bar.addView(name, new LinearLayout.LayoutParams(0, dp(56), 1)); return bar;
    }
    private View toolbarIcon(int kind, String description, Runnable action) {
        View icon = new View(this) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas); canvas.save(); canvas.translate(getWidth() / 2f, getHeight() / 2f);
                float density = getResources().getDisplayMetrics().density; canvas.scale(density, density);
                paint.setColor(MUTED); paint.setStrokeWidth(2); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStyle(Paint.Style.STROKE);
                if (kind == 0) { for (int y = -5; y <= 5; y += 5) canvas.drawLine(-8, y, 8, y, paint); }
                else if (kind == 1) {
                    canvas.drawCircle(0, 0, 9, paint); paint.setStyle(Paint.Style.FILL); paint.setTextSize(15); paint.setTextAlign(Paint.Align.CENTER);
                    canvas.drawText("?", 0, -(paint.ascent() + paint.descent()) / 2f, paint);
                } else if (kind == 2) { paint.setStyle(Paint.Style.FILL); for (int y = -6; y <= 6; y += 6) canvas.drawCircle(0, y, 1.8f, paint); }
                else { canvas.drawLine(7, 0, -7, 0, paint); canvas.drawLine(-7, 0, -1, -6, paint); canvas.drawLine(-7, 0, -1, 6, paint); }
                canvas.restore();
            }
        };
        icon.setContentDescription(description); icon.setFocusable(true);
        icon.setBackground(Compatibility.ripple(0x22000000, null, rounded(Color.WHITE, 24)));
        icon.setOnClickListener(v -> action.run()); return icon;
    }
    private void showScreen() {
        viewing = true; firstFrame = false; frameWidth = frameHeight = 0; root.removeAllViews();
        LinearLayout screen = column(); root.addView(screen, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout bar = toolbar("远程控制", this::confirmExit); bar.addView(toolbarIcon(2, "更多选项", this::sessionMenu), new LinearLayout.LayoutParams(dp(48), dp(48))); screen.addView(bar);
        status = label("正在准备显示区域…", 12); status.setTextColor(0xff666666); screen.addView(status);
        picture = new FrameLayout(this); picture.setBackgroundColor(Color.BLACK); screen.addView(picture, new LinearLayout.LayoutParams(-1, 0, 1));
        texture = new TextureView(this); texture.setSurfaceTextureListener(this); picture.addView(texture, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        screenshot = new ImageView(this); screenshot.setScaleType(options.stretch ? ImageView.ScaleType.FIT_XY : ImageView.ScaleType.FIT_CENTER);
        picture.addView(screenshot, new FrameLayout.LayoutParams(-1, -1));
        screenshot.setVisibility(options.compatibility ? View.VISIBLE : View.GONE); texture.setVisibility(options.compatibility ? View.GONE : View.VISIBLE);
        View touches = new View(this); touches.setContentDescription("目标屏幕触控区域"); touches.setOnTouchListener(this::touch);
        picture.addView(touches, new FrameLayout.LayoutParams(-1, -1));
        picture.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> resizeVideo());
        bottomNav = navigation();
        if (navigationIndex == 0) screen.addView(bottomNav);
        else if (navigationIndex == 1) {
            FrameLayout floating = new FrameLayout(this); floating.setBackgroundColor(0xeefafafa);
            LinearLayout row = new LinearLayout(this); TextView handle = label("⋮⋮", 20); row.addView(handle); row.addView(bottomNav); floating.addView(row);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); lp.bottomMargin = dp(20); picture.addView(floating, lp);
            handle.setOnTouchListener(new View.OnTouchListener() {
                float dx, dy;
                @Override public boolean onTouch(View view, android.view.MotionEvent e) {
                    if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) { dx = floating.getX() - e.getRawX(); dy = floating.getY() - e.getRawY(); return true; }
                    if (e.getActionMasked() == android.view.MotionEvent.ACTION_MOVE) {
                        floating.setX(Math.max(0, Math.min(picture.getWidth() - floating.getWidth(), e.getRawX() + dx)));
                        floating.setY(Math.max(0, Math.min(picture.getHeight() - floating.getHeight(), e.getRawY() + dy))); return true;
                    }
                    return true;
                }
            });
        }
        applyFullscreen(fullScreen); applyOrientation();
        if (options.compatibility) startIfReady();
    }
    private LinearLayout navigation() {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER); row.setBackgroundColor(0xffeeeeee);
        row.addView(button("返回", () -> controller.key(4))); row.addView(button("主页", () -> controller.key(3))); row.addView(button("最近", () -> controller.key(187))); return row;
    }
    private void sessionMenu() {
        String[] items = {"重新连接", "保存截图", "输入文本", "全屏开关", "本机显示方向", "旋转目标屏幕", "目标物理屏幕", "连接参数", "控制帮助", "退出远程控制"};
        new AlertDialog.Builder(this).setTitle("远程控制").setItems(items, (d, which) -> {
            switch (which) {
                case 0: releasePointers(); controller.stop(); startIfReady(); break;
                case 1: captureExport(); break;
                case 2: inputText(); break;
                case 3: fullScreen = !fullScreen; saveSettings(); applyFullscreen(fullScreen); break;
                case 4: chooseOrientation(); break;
                case 5: try { controller.rotateDevice(); } catch (RuntimeException e) { onError(e.getMessage()); } break;
                case 6: choosePower(); break;
                case 7: showSettings(); break;
                case 8: help(); break;
                case 9: confirmExit(); break;
                default: break;
            }
        }).show();
    }
    private void advanced() { RemoteSettingsView.showAdvanced(this); }
    private void chooseOrientation() {
        new AlertDialog.Builder(this).setTitle("本机显示方向").setSingleChoiceItems(new String[]{"跟随系统", "竖屏", "横屏"}, orientationIndex, (d, w) -> {
            orientationIndex = w; saveSettings(); applyOrientation(); d.dismiss();
        }).setNegativeButton("取消", null).show();
    }
    private void applyOrientation() { setRequestedOrientation(orientationIndex == 1 ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : orientationIndex == 2 ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED); }
    private void choosePower() {
        if (options.compatibility) { onError("兼容模式不支持独立熄屏控制"); return; }
        new AlertDialog.Builder(this).setTitle("目标物理屏幕").setMessage("只改变物理屏幕显示，继续投屏；正常退出时尝试恢复。")
                .setNegativeButton("取消", null).setNeutralButton("点亮", (d,w) -> controller.screenPower(true)).setPositiveButton("熄灭", (d,w) -> controller.screenPower(false)).show();
    }
    private void help() { RemoteSettingsView.showHelp(this); }
    private void inputText() {
        EditText input = new EditText(this); input.setHint(options.compatibility ? "可打印英文字符，不含 %" : "输入要发送到目标设备的文本"); input.setMinLines(2);
        LinearLayout layout = column(); layout.setPadding(dp(16),0,dp(16),0); layout.addView(input);
        CheckBox paste = check("粘贴（覆盖目标剪贴板，可发送中文）", false); paste.setEnabled(!options.compatibility); layout.addView(paste);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("输入文本").setView(layout).setNegativeButton("取消", null).setPositiveButton("发送", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try { if (paste.isChecked()) controller.pasteText(input.getText().toString()); else controller.text(input.getText().toString()); dialog.dismiss(); }
            catch (RuntimeException e) { input.setError(e.getMessage()); }
        })); dialog.show();
    }
    private void captureExport() {
        if (exporting) return; exporting = true; onStatus("正在获取当前 PNG 截图…");
        controller.capture((png, error) -> {
            exporting = false; if (error != null) { onError(error); return; }
            if (ScreenshotStore.hasDirectory(this)) { new Thread(() -> { try { ScreenshotStore.saveConfigured(this,png); runOnUiThread(() -> {if(!isFinishing()&&!isDestroyed())onStatus("截图已保存到设置目录");}); } catch(IOException e) { runOnUiThread(() -> {if(!isFinishing()&&!isDestroyed())onError(e.getMessage());}); } },"screenshot-save").start(); return; }
            exportPng = png;
            Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/png")
                    .putExtra(Intent.EXTRA_TITLE, "金甲虫ADB助手截图-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".png");
            try { startActivityForResult(save, EXPORT_PNG); } catch (RuntimeException e) { exportPng = null; onError("无法打开系统保存位置：" + e.getMessage()); }
        });
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != EXPORT_PNG) return;
        final byte[] png = exportPng; exportPng = null;
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (png == null) { onError("截图已在页面重建后失效，请重新保存"); return; }
        Uri uri = data.getData();
        new Thread(() -> {
            try (OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
                if (output == null) throw new IOException("无法写入所选文件"); output.write(png); output.flush();
                runOnUiThread(() -> { if (!isFinishing()) Toast.makeText(this, "截图已保存", Toast.LENGTH_SHORT).show(); });
            } catch (Exception e) { runOnUiThread(() -> { if (!isFinishing()) onError("保存失败：" + e.getMessage()); }); }
        }, "jinjiachong-png-export").start();
    }
    private boolean touch(View v, android.view.MotionEvent e) {
        if (!viewing || frameWidth == 0 || frameHeight == 0 || !controller.running()) return true;
        int action = e.getActionMasked(), index = e.getActionIndex();
        if (options.compatibility) {
            if (action == android.view.MotionEvent.ACTION_DOWN) { startPoint = point(e, 0); downAt = SystemClock.uptimeMillis(); compatibleGesture = startPoint != null; multiplePointers = false; }
            else if (action == android.view.MotionEvent.ACTION_POINTER_DOWN) multiplePointers = true;
            else if (action == android.view.MotionEvent.ACTION_CANCEL) compatibleGesture = false;
            else if (action == android.view.MotionEvent.ACTION_UP && compatibleGesture) {
                int[] end = point(e, 0); compatibleGesture = false;
                if (multiplePointers || end == null) return true;
                int duration = (int) Math.min(10000, SystemClock.uptimeMillis() - downAt);
                if (Math.hypot(end[0] - startPoint[0], end[1] - startPoint[1]) < 8 && duration < 450) controller.tap(end[0], end[1]);
                else controller.swipe(startPoint[0], startPoint[1], end[0], end[1], Math.max(100, duration));
            }
            return true;
        }
        if (action == android.view.MotionEvent.ACTION_DOWN || action == android.view.MotionEvent.ACTION_POINTER_DOWN) {
            int[] p = point(e, index); if (p != null && pointers.size() < 10) { int id = e.getPointerId(index); pointers.put(id, p); sendTouch(0, id, p, e.getPressure(index)); }
        } else if (action == android.view.MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < e.getPointerCount(); i++) {
                int id = e.getPointerId(i); if (!pointers.containsKey(id)) continue;
                int[] p = point(e, i); if (p == null) p = pointers.get(id);
                pointers.put(id, p); sendTouch(2, id, p, e.getPressure(i));
            }
        } else if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_POINTER_UP) {
            int id = e.getPointerId(index); int[] old = pointers.remove(id); if (old != null) { int[] p = point(e, index); sendTouch(1, id, p == null ? old : p, 0); }
        } else if (action == android.view.MotionEvent.ACTION_CANCEL) releasePointers();
        return true;
    }
    private int[] point(android.view.MotionEvent e, int index) { return ScrcpyProtocol.map(e.getX(index), e.getY(index), picture.getWidth(), picture.getHeight(), frameWidth, frameHeight, options.stretch); }
    private void sendTouch(int action, int id, int[] p, float pressure) { controller.touch(action, id, p[0], p[1], frameWidth, frameHeight, pressure); }
    private void releasePointers() {
        if (controller != null && frameWidth > 0 && frameHeight > 0) for (Map.Entry<Integer,int[]> p : pointers.entrySet()) sendTouch(1,p.getKey(),p.getValue(),0);
        pointers.clear(); compatibleGesture = false;
    }
    private void startIfReady() {
        if (!resumed || !viewing || controller == null || controller.running()) return;
        if (!options.compatibility && (surface == null || !surface.isValid())) return;
        frameWidth = frameHeight = 0; firstFrame = false;
        if (status != null) status.setTextColor(0xff666666);
        if (screenshot != null) screenshot.setImageDrawable(null);
        if (shownBitmap != null) { shownBitmap.recycle(); shownBitmap = null; }
        try { controller.start(options, surface); } catch (RuntimeException e) { onError(e.getMessage()); }
    }
    @Override public void onStatus(String text) { if (status != null) status.setText(text); }
    @Override public void onError(String error) {
        if (status != null) { status.setText("错误：" + error); status.setTextColor(0xffb3261e); }
        Toast.makeText(this, error == null ? "远控失败" : error, Toast.LENGTH_LONG).show();
    }
    @Override public void onFrame(Bitmap bitmap, int width, int height) {
        if (!viewing) { bitmap.recycle(); return; }
        updateSize(width, height); Bitmap old = shownBitmap; shownBitmap = bitmap; screenshot.setImageBitmap(bitmap);
        if (old != null && old != bitmap) old.recycle();
        if (!firstFrame) { firstFrame = true; onStatus("兼容模式 · " + width + " × " + height + " · 单指截图控制"); }
    }
    @Override public void onVideoSize(int width, int height) { updateSize(width, height); resizeVideo(); }
    private void updateSize(int width, int height) {
        if ((frameWidth != width || frameHeight != height) && frameWidth > 0 && frameHeight > 0) {
            // The server rejects old-size coordinates after rotation; release using the new size.
            for (Map.Entry<Integer,int[]> p : pointers.entrySet()) {
                int x = Math.min(width - 1, (int) ((long) p.getValue()[0] * width / frameWidth));
                int y = Math.min(height - 1, (int) ((long) p.getValue()[1] * height / frameHeight));
                controller.touch(1, p.getKey(), x, y, width, height, 0);
            }
            pointers.clear(); compatibleGesture = false;
        }
        frameWidth = width; frameHeight = height;
    }
    private void resizeVideo() {
        if (picture == null || texture == null || frameWidth <= 0 || frameHeight <= 0) return;
        int w = picture.getWidth(), h = picture.getHeight(); if (w <= 0 || h <= 0) return;
        if (!options.stretch) { double scale = Math.min((double)w / frameWidth, (double)h / frameHeight); w = Math.max(1, (int)(frameWidth * scale)); h = Math.max(1, (int)(frameHeight * scale)); }
        FrameLayout.LayoutParams old = (FrameLayout.LayoutParams)texture.getLayoutParams();
        if (old.width != w || old.height != h) texture.setLayoutParams(new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
    }
    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) { if (surface != null) surface.release(); activeTexture = texture; surface = new Surface(texture); startIfReady(); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) { }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) { if (texture == activeTexture) { if (controller != null) controller.stop(); disposeSurface(); } return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) { }
    private void disposeSurface() { activeTexture = null; if (surface != null) { surface.release(); surface = null; } }
    private void applyFullscreen(boolean enabled) {
        getWindow().getDecorView().setSystemUiVisibility(enabled ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        if (enabled) getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN); else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }
    @Override public void onWindowFocusChanged(boolean hasFocus) { super.onWindowFocusChanged(hasFocus); if (hasFocus && viewing) applyFullscreen(fullScreen); }
    @Override protected void onResume() { super.onResume(); resumed = true; startIfReady(); }
    @Override protected void onPause() { resumed = false; releasePointers(); exporting = false; if (controller != null) controller.stop(); super.onPause(); }
    @Override protected void onDestroy() { if (controller != null) controller.close(); disposeSurface(); if (shownBitmap != null) { if (screenshot != null) screenshot.setImageDrawable(null); shownBitmap.recycle(); shownBitmap = null; } super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle out) { out.putBoolean("viewing", viewing); super.onSaveInstanceState(out); }
    @Override public void onBackPressed() { if (viewing) confirmExit(); else super.onBackPressed(); }
    private void confirmExit() { new AlertDialog.Builder(this).setTitle("退出远程控制？").setMessage("将停止投屏并释放控制会话，ADB 连接仍可用于其他功能。")
            .setNegativeButton("取消", null).setPositiveButton("退出", (d,w) -> { viewing = false; releasePointers(); controller.stop(); finish(); }).show(); }
    private void setResolution() { options.shortSide = resolutionIndex > 1 ? new int[]{1080,720,540,360}[resolutionIndex-2] : 0; options.maxDimension = resolutionIndex == 0 ? 0 : 1920; }
    private void readSettings() {
        resolutionIndex = Math.max(0,Math.min(5,prefs.getInt("resolution",0))); setResolution(); options.bitRate = prefs.getInt("bitrate",8000000);
        options.compatibility = prefs.getBoolean("compatibility",false); options.stretch = prefs.getBoolean("stretch",false);
        options.turnScreenOff = prefs.getBoolean("screen_off",false) && !options.compatibility; options.stayAwake = prefs.getBoolean("awake",false); options.renderAllFrames = prefs.getBoolean("all_frames",false);
        options.refreshIntervalMs = Math.max(250,Math.min(10000,prefs.getInt("interval",700))); fullScreen = prefs.getBoolean("fullscreen",false);
        navigationIndex = Math.max(0,Math.min(2,prefs.getInt("navigation",1))); orientationIndex = Math.max(0,Math.min(2,prefs.getInt("orientation",0)));
    }
    private void saveSettings() { prefs.edit().putInt("resolution",resolutionIndex).putInt("bitrate",options.bitRate).putBoolean("compatibility",options.compatibility).putBoolean("stretch",options.stretch)
            .putBoolean("screen_off",options.turnScreenOff).putBoolean("awake",options.stayAwake).putBoolean("all_frames",options.renderAllFrames).putInt("interval",options.refreshIntervalMs)
            .putBoolean("fullscreen",fullScreen).putInt("navigation",navigationIndex).putInt("orientation",orientationIndex).apply(); }
}
