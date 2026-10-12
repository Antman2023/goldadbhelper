package io.github.devicebridge.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import io.github.devicebridge.core.AndroidFileAccess;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Local-only picker for FILE-16/17. It never connects to a remote device or starts a transfer.
 * The non-exported caller must keep its own pending operation/session binding and validate it
 * after every result, including RESULT_USE_SAF. File URIs in results are for this package only.
 */
public final class LocalFilesActivity extends Activity {
    public static final int MODE_FILE = LocalFileBrowserModel.MODE_FILE;
    public static final int MODE_FILES = LocalFileBrowserModel.MODE_FILES;
    public static final int MODE_DIRECTORY = LocalFileBrowserModel.MODE_DIRECTORY;
    public static final int MODE_APK = LocalFileBrowserModel.MODE_APK;
    public static final int MODE_IMAGE = LocalFileBrowserModel.MODE_IMAGE;
    public static final int RESULT_USE_SAF = RESULT_FIRST_USER + 21;
    public static final String EXTRA_MODE = "local_picker_mode";
    public static final String EXTRA_REQUEST_ID = "local_picker_request_id";
    public static final String EXTRA_ALLOW_MULTIPLE = "local_picker_allow_multiple";
    public static final String EXTRA_PATHS = "local_picker_paths";
    /** Kernel identity tokens, aligned exactly with EXTRA_PATHS. Never synthesize missing tokens. */
    public static final String EXTRA_SOURCE_TOKENS = "local_picker_source_tokens";
    public static final String EXTRA_DIRECTORY = "local_picker_directory";
    private static final int STORAGE_PERMISSION = 71, STORAGE_SETTINGS = 72;
    // Bound Binder/saved-state size instead of imposing the reference application's paid limit.
    private static final int MAX_RESULT_WEIGHT = 48000;
    private static final int INK = 0xff212121, MUTED = 0xff757575, BLUE = 0xff2196f3;
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<File> roots = new ArrayList<>();
    private final List<String> rootNames = new ArrayList<>();
    private final List<LocalFileBrowserModel.Entry> entries = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private final Map<String, Integer> scrollPositions = new HashMap<>();
    private final Map<String, String> displayedFileTokens = new HashMap<>();
    private SharedPreferences prefs;
    private File directory, navigationRoot;
    private int mode, sort, loadGeneration, confirmationGeneration, restoredScroll;
    private String requestId, permissionSnapshot, directoryIdentity;
    private boolean multiple, hidden, descending, loading, confirming, readableDirectory, destroyed, settingsOpened;
    private Future<?> loadTask, confirmationTask;
    private TextView pathLabel, summary, accessHint, empty;
    private Button up, confirm, grant;
    private ListView list;
    private LocalAdapter adapter;

    public static Intent intent(Context context, int mode, String requestId) {
        return new Intent(context, LocalFilesActivity.class)
                .putExtra(EXTRA_MODE, LocalFileBrowserModel.checkedMode(mode))
                .putExtra(EXTRA_REQUEST_ID, requestId);
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        // Defense in depth: no path results can be returned to another package.
        String caller = getCallingPackage();
        if (caller != null && !getPackageName().equals(caller)) { finish(); return; }
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        mode = LocalFileBrowserModel.checkedMode(getIntent().getIntExtra(EXTRA_MODE, MODE_FILE));
        requestId = getIntent().getStringExtra(EXTRA_REQUEST_ID);
        if (requestId == null) requestId = saved == null ? UUID.randomUUID().toString()
                : saved.getString(EXTRA_REQUEST_ID, UUID.randomUUID().toString());
        multiple = mode != MODE_DIRECTORY && getIntent().getBooleanExtra(EXTRA_ALLOW_MULTIPLE,
                mode == MODE_FILES || mode == MODE_APK);
        hidden = prefs.getBoolean("local_show_hidden", prefs.getBoolean("show_hidden", false));
        sort = prefs.getInt("local_sort", LocalFileBrowserModel.SORT_NAME);
        if (sort < 0 || sort > 3) sort = 0;
        descending = prefs.getBoolean("local_sort_descending", false);
        discoverRoots();
        String remembered = saved == null ? prefs.getString(lastDirectoryKey(), null)
                : saved.getString("directory");
        directory = restoreDirectory(remembered);
        if (saved != null) {
            ArrayList<String> paths = saved.getStringArrayList("selected");
            ArrayList<String> tokens = saved.getStringArrayList(EXTRA_SOURCE_TOKENS);
            if (paths != null && tokens != null && paths.size() == tokens.size()
                    && selectionWeight(paths) + selectionWeight(tokens) <= MAX_RESULT_WEIGHT) {
                for (int i = 0; i < paths.size(); i++) {
                    selected.add(paths.get(i)); displayedFileTokens.put(paths.get(i), tokens.get(i));
                }
            }
            if (directory != null && directory.getAbsolutePath().equals(remembered))
                directoryIdentity = saved.getString("directory_identity");
            restoredScroll = saved.getInt("scroll", 0);
            String rootPath = saved.getString("navigation_root");
            if (rootPath != null) for (File root : roots)
                if (root.getAbsolutePath().equals(rootPath)) navigationRoot = root;
        }
        if (directory != null && (navigationRoot == null || !isInside(navigationRoot, directory)))
            navigationRoot = findRoot(directory);
        createView();
        setResult(RESULT_CANCELED, resultMetadata());
        permissionSnapshot = permissionState();
        reload(true);
    }

    private String lastDirectoryKey() { return "local_last_directory_" + mode; }

    private void createView() {
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(0xffffffff);
        if (Build.VERSION.SDK_INT >= 20) Ui.inset(this, root); else root.setFitsSystemWindows(true);
        LinearLayout toolbar = Ui.row(this);
        TextView back = action("‹", "返回上级或取消", this::onBackPressed);
        back.setTextSize(32);
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(52), dp(56)));
        TextView title = label(mode == MODE_DIRECTORY ? "选择本机目录" : mode == MODE_APK ? "选择APK"
                : mode == MODE_IMAGE ? "选择镜像" : "选择本机文件", 20, INK);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        toolbar.addView(new IconView(this, IconView.Kind.SORT, MUTED, "排序", this::sortDialog),
                new LinearLayout.LayoutParams(dp(48), dp(56)));
        toolbar.addView(new IconView(this, IconView.Kind.MORE, MUTED, "更多选项", this::menu),
                new LinearLayout.LayoutParams(dp(48), dp(56)));
        root.addView(toolbar);

        LinearLayout pathRow = Ui.row(this);
        pathRow.setBackgroundColor(0xffeeeeee);
        pathLabel = label("", 14, INK);
        pathLabel.setSingleLine(true);
        pathLabel.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        pathLabel.setPadding(dp(12), 0, dp(8), 0);
        pathLabel.setContentDescription("当前目录，点击输入路径");
        pathLabel.setFocusable(true);
        pathLabel.setOnClickListener(v -> enterPath());
        pathRow.addView(pathLabel, new LinearLayout.LayoutParams(0, dp(48), 1));
        up = Ui.button(this, "上级", this::goUp);
        pathRow.addView(up, new LinearLayout.LayoutParams(dp(72), dp(48)));
        root.addView(pathRow);

        accessHint = label("", 12, MUTED);
        accessHint.setPadding(dp(12), dp(6), dp(12), dp(4));
        root.addView(accessHint);
        LinearLayout accessRow = Ui.row(this);
        accessRow.addView(Ui.button(this, "存储位置", this::chooseRoot), new LinearLayout.LayoutParams(0, dp(48), 1));
        accessRow.addView(Ui.button(this, "系统选择器", this::useSaf), new LinearLayout.LayoutParams(0, dp(48), 1));
        grant = Ui.button(this, "存储权限", this::requestStorage);
        accessRow.addView(grant, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(accessRow);

        FrameLayout body = new FrameLayout(this);
        list = new ListView(this);
        list.setDivider(null);
        list.setFastScrollEnabled(true);
        adapter = new LocalAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> click(entries.get(position)));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            LocalFileBrowserModel.Entry entry = entries.get(position);
            if (entry.directory) showDetails(entry); else if (mode != MODE_DIRECTORY) toggle(entry);
            return true;
        });
        body.addView(list, new FrameLayout.LayoutParams(-1, -1));
        empty = label("", 15, MUTED);
        empty.setPadding(dp(24), dp(12), dp(24), dp(12));
        empty.setGravity(Gravity.CENTER);
        body.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        list.setEmptyView(empty);
        Ui.fill(root, body);
        summary = label("", 13, MUTED);
        summary.setPadding(dp(16), dp(5), dp(16), dp(5));
        root.addView(summary);
        LinearLayout bottom = Ui.row(this);
        bottom.addView(Ui.button(this, "取消", this::cancel), new LinearLayout.LayoutParams(0, dp(48), 1));
        confirm = Ui.button(this, "确定", this::finishSelection);
        bottom.addView(confirm, new LinearLayout.LayoutParams(0, dp(48), 2));
        root.addView(bottom);
        setContentView(root);
    }

    private int dp(float value) { return Ui.dp(this, value); }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size); view.setTextColor(color);
        return view;
    }

    private TextView action(String text, String description, Runnable action) {
        TextView view = label(text, 16, BLUE);
        view.setGravity(Gravity.CENTER);
        view.setContentDescription(description);
        view.setFocusable(true); view.setClickable(true);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private void discoverRoots() {
        roots.clear(); rootNames.clear();
        addRoot(Environment.getExternalStorageDirectory(), "内部共享存储");
        File[] ownDirectories = getExternalFilesDirs(null); // Available since API 19.
        if (ownDirectories != null) for (int i = 0; i < ownDirectories.length; i++) {
            File own = ownDirectories[i];
            if (own == null) continue;
            String suffix = File.separator + "Android" + File.separator + "data" + File.separator
                    + getPackageName() + File.separator + "files";
            String path = own.getAbsolutePath();
            if (path.endsWith(suffix)) addRoot(new File(path.substring(0, path.length() - suffix.length())),
                    i == 0 ? "内部共享存储" : "外部存储 " + i);
            addRoot(own, i == 0 ? "金甲虫ADB助手文件（卸载时删除）" : "金甲虫ADB助手外部文件 " + i + "（卸载时删除）");
        }
    }

    private void addRoot(File root, String name) {
        if (root == null) return;
        try {
            File canonical = root.getCanonicalFile();
            if (!roots.contains(canonical)) { roots.add(canonical); rootNames.add(name); }
        } catch (IOException | SecurityException ignored) { /* unavailable storage */ }
    }

    private File restoreDirectory(String path) {
        if (path != null) try {
            File candidate = LocalFileBrowserModel.requireAllowed(new File(path), roots);
            if (candidate.equals(new File(path).getAbsoluteFile()) && candidate.isDirectory()
                    && candidate.canRead()) return candidate;
        } catch (IOException | SecurityException ignored) { /* stale preference is not an error */ }
        for (File root : roots) if (root.isDirectory() && root.canRead()) return root;
        return roots.isEmpty() ? null : roots.get(0);
    }

    private File findRoot(File path) {
        // Prefer the broad storage root when there is one, retaining parent navigation.
        for (File root : roots) if (isInside(root, path)) return root;
        return path;
    }

    private boolean isInside(File root, File file) {
        try { return LocalFileBrowserModel.inside(root, file); }
        catch (IOException | SecurityException error) { return false; }
    }

    private void navigate(File next, File requestedRoot) {
        if (confirming) return;
        if (loading) { loadGeneration++; if (loadTask != null) loadTask.cancel(true); }
        try {
            File allowed = LocalFileBrowserModel.requireAllowed(next, roots);
            if (!allowed.isDirectory()) throw new IOException("此目录不存在或不可访问");
            rememberScroll();
            selected.clear();
            directory = allowed;
            directoryIdentity = null; displayedFileTokens.clear();
            navigationRoot = requestedRoot == null || !isInside(requestedRoot, allowed)
                    ? findRoot(allowed) : requestedRoot;
            restoredScroll = scrollPositions.containsKey(allowed.getAbsolutePath())
                    ? scrollPositions.get(allowed.getAbsolutePath()) : 0;
            reload(true);
        } catch (IOException | SecurityException error) {
            Ui.message(this, "无法进入目录", error.getMessage());
            if (loading) reload(false);
        }
    }

    private void rememberScroll() {
        if (directory != null && list != null)
            scrollPositions.put(directory.getAbsolutePath(), list.getFirstVisiblePosition());
    }

    private void reload(boolean restoreScroll) {
        if (destroyed || adapter == null) return;
        stopConfirmation();
        final int generation = ++loadGeneration;
        if (loadTask != null) loadTask.cancel(true);
        final File target = directory;
        final List<File> allowedRoots = new ArrayList<>(roots);
        final int pickerMode = mode, sortMode = sort;
        final boolean showHidden = hidden, reverse = descending;
        final String expectedDirectory = directoryIdentity;
        final Map<String, String> previousTokens = new HashMap<>(displayedFileTokens);
        final int scroll = restoreScroll ? restoredScroll : list.getFirstVisiblePosition();
        loading = true; readableDirectory = false;
        entries.clear(); adapter.notifyDataSetChanged();
        empty.setText("正在读取目录…");
        updateControls();
        if (target == null) { loading = false; empty.setText("没有可用的本机存储，请使用系统选择器。"); updateControls(); return; }
        loadTask = reader.submit(() -> {
            List<LocalFileBrowserModel.Entry> found = Collections.emptyList();
            Map<String, String> tokens = new HashMap<>();
            String boundDirectory = null;
            String error = null;
            try {
                if (!LocalFileBrowserModel.requireAllowed(target, allowedRoots).equals(target.getAbsoluteFile()))
                    throw new IOException("目录路径已变化，请重新选择存储位置");
                boundDirectory = Compatibility.directoryToken(target);
                if (expectedDirectory != null && !expectedDirectory.equals(boundDirectory))
                    throw new IOException("原目录已被替换或移动，请重新选择存储位置");
                found = LocalFileBrowserModel.list(target, allowedRoots, pickerMode, showHidden,
                        sortMode, reverse, Locale.getDefault());
                AndroidFileAccess fs = new AndroidFileAccess();
                for (LocalFileBrowserModel.Entry entry : found) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("读取已取消");
                    if (!entry.directory) {
                        AndroidFileAccess.Stat stat = fs.lstat(entry.path);
                        if (!stat.regular || stat.size != entry.size)
                            throw new IOException("目录内容在读取期间改变，请刷新后重新选择");
                        tokens.put(entry.path, stat.token());
                    }
                }
                if (!boundDirectory.equals(Compatibility.directoryToken(target)))
                    throw new IOException("目录在读取期间被替换，请重新选择存储位置");
            } catch (IOException | SecurityException failure) {
                error = failure.getMessage(); found = Collections.emptyList(); tokens.clear();
            }
            final List<LocalFileBrowserModel.Entry> result = found;
            final Map<String, String> resultTokens = tokens;
            final String resultDirectory = boundDirectory;
            final String failure = error;
            main.post(() -> {
                if (destroyed || isFinishing() || generation != loadGeneration) return;
                loading = false; readableDirectory = failure == null;
                entries.addAll(result);
                Set<String> visible = new LinkedHashSet<>();
                for (LocalFileBrowserModel.Entry entry : entries) if (!entry.directory) visible.add(entry.path);
                selected.retainAll(visible);
                for (String path : new ArrayList<>(selected))
                    if (!resultTokens.containsKey(path) || previousTokens.containsKey(path)
                            && !previousTokens.get(path).equals(resultTokens.get(path))) selected.remove(path);
                displayedFileTokens.clear(); displayedFileTokens.putAll(resultTokens);
                if (!multiple && selected.size() > 1) selected.clear();
                empty.setText(failure == null ? (mode == MODE_APK ? "此目录没有APK，可返回上级或切换存储位置。"
                        : mode == MODE_DIRECTORY ? "没有子文件夹，可选择当前目录。" : "此目录为空。") : failure);
                adapter.notifyDataSetChanged();
                if (failure == null) {
                    directoryIdentity = resultDirectory;
                    prefs.edit().putString(lastDirectoryKey(), target.getAbsolutePath()).apply();
                    list.setSelection(Math.max(0, scroll));
                }
                updateControls();
                if (failure != null) confirm.setEnabled(false);
            });
        });
    }

    private void updateControls() {
        if (pathLabel == null) return;
        pathLabel.setText(directory == null ? "没有可用存储" : directory.getAbsolutePath());
        pathLabel.setContentDescription("当前目录：" + pathLabel.getText() + "。点击输入路径");
        up.setEnabled(!confirming && directory != null && navigationRoot != null && !directory.equals(navigationRoot));
        pathLabel.setEnabled(!confirming); list.setEnabled(!confirming); grant.setEnabled(!confirming);
        summary.setText(confirming ? "正在核对所选项目…" : loading ? "读取中…" : mode == MODE_DIRECTORY ? entries.size() + " 个子文件夹"
                : entries.size() + " 项 · 已选择 " + selected.size() + " 项" + (multiple ? " · 点击文件选择" : " · 单选"));
        confirm.setText(mode == MODE_DIRECTORY ? "选择此目录" : "确定（" + selected.size() + "）");
        confirm.setEnabled(!loading && !confirming && readableDirectory && (mode == MODE_DIRECTORY ? directory != null && directory.canWrite()
                : !selected.isEmpty()));
        accessHint.setText(accessDescription());
        grant.setText(hasStorageAccess() ? "权限说明" : "存储授权");
    }

    private void click(LocalFileBrowserModel.Entry entry) {
        if (loading || confirming) return;
        if (entry.directory) navigate(entry.file, navigationRoot);
        else toggle(entry);
    }

    private void toggle(LocalFileBrowserModel.Entry entry) {
        if (loading || confirming || entry.directory || mode == MODE_DIRECTORY) return;
        if (selected.remove(entry.path)) { adapter.notifyDataSetChanged(); updateControls(); return; }
        if (!multiple) selected.clear();
        if (selectionWeight(selected) + entry.path.length() + 64 > MAX_RESULT_WEIGHT) {
            Toast.makeText(this, "所选路径已达到系统返回容量上限，请分批选择。", Toast.LENGTH_LONG).show();
            return;
        }
        selected.add(entry.path);
        adapter.notifyDataSetChanged(); updateControls();
    }

    private static int selectionWeight(Iterable<String> paths) {
        int weight = 0;
        for (String path : paths) {
            if (path == null) return Integer.MAX_VALUE;
            weight += path.length() + 64;
            if (weight > MAX_RESULT_WEIGHT) break;
        }
        return weight;
    }

    private void selectVisible(boolean invert) {
        if (!multiple || loading || confirming) return;
        Set<String> next = new LinkedHashSet<>();
        for (LocalFileBrowserModel.Entry entry : entries)
            if (!entry.directory && (!invert || !selected.contains(entry.path))) next.add(entry.path);
        if (selectionWeight(next) > MAX_RESULT_WEIGHT) {
            Ui.message(this, "请分批选择", "所选路径超过系统一次返回的容量。当前选择保持不变。"); return;
        }
        selected.clear(); selected.addAll(next);
        adapter.notifyDataSetChanged(); updateControls();
    }

    private void menu() {
        if (confirming) return;
        List<String> items = new ArrayList<>();
        items.add(hidden ? "隐藏点开头文件" : "显示点开头文件");
        items.add("刷新"); items.add("输入路径");
        if (multiple) { items.add("全选当前目录文件"); items.add("反选当前目录文件"); }
        items.add("清除选择");
        new AlertDialog.Builder(this).setTitle("本机文件").setItems(items.toArray(new String[0]), (d, which) -> {
            if (which == 0) { hidden = !hidden; prefs.edit().putBoolean("local_show_hidden", hidden).apply(); reload(false); }
            else if (which == 1) reload(false);
            else if (which == 2) enterPath();
            else if (multiple && which == 3) selectVisible(false);
            else if (multiple && which == 4) selectVisible(true);
            else { selected.clear(); adapter.notifyDataSetChanged(); updateControls(); }
        }).setNegativeButton("关闭", null).show();
    }

    private void sortDialog() {
        if (confirming) return;
        String[] orders = {"名称 · 升序", "名称 · 降序", "类型 · 升序", "类型 · 降序",
                "时间 · 最早优先", "时间 · 最新优先", "大小 · 最小优先", "大小 · 最大优先"};
        new AlertDialog.Builder(this).setTitle("排序（文件夹始终在前）")
                .setSingleChoiceItems(orders, sort * 2 + (descending ? 1 : 0), (dialog, which) -> {
                    sort = which / 2; descending = which % 2 == 1;
                    prefs.edit().putInt("local_sort", sort).putBoolean("local_sort_descending", descending).apply();
                    dialog.dismiss(); restoredScroll = 0; reload(true);
                }).setNegativeButton("取消", null).show();
    }

    private void chooseRoot() {
        if (confirming) return;
        discoverRoots();
        if (roots.isEmpty()) { Ui.message(this, "没有可用存储", "请检查存储设备，或使用系统选择器。"); return; }
        String[] labels = new String[roots.size()];
        for (int i = 0; i < roots.size(); i++) labels[i] = rootNames.get(i) + "\n" + roots.get(i).getAbsolutePath();
        new AlertDialog.Builder(this).setTitle("选择存储位置").setItems(labels,
                (d, which) -> navigate(roots.get(which), roots.get(which))).setNegativeButton("取消", null).show();
    }

    private void enterPath() {
        if (confirming) return;
        EditText input = Ui.edit(this, "输入本机目录的完整路径");
        input.setText(directory == null ? "" : directory.getAbsolutePath());
        input.setSelectAllOnFocus(true);
        LinearLayout panel = Ui.col(this);
        panel.setPadding(dp(20), dp(8), dp(20), 0); panel.addView(input);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("前往目录").setView(panel)
                .setNegativeButton("取消", null).setPositiveButton("打开", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                // A trailing space can be part of a real filename; do not rewrite the user's path.
                File candidate = new File(input.getText().toString());
                if (!candidate.isAbsolute()) throw new IOException("请输入完整绝对路径");
                candidate = LocalFileBrowserModel.requireAllowed(candidate, roots);
                if (!candidate.isDirectory()) throw new IOException("目录不存在或不可访问");
                dialog.dismiss(); navigate(candidate, null);
            } catch (IOException | SecurityException error) { input.setError(error.getMessage()); }
        }));
        dialog.show();
    }

    private void goUp() {
        if (confirming) return;
        if (directory == null || navigationRoot == null || directory.equals(navigationRoot)) return;
        File parent = directory.getParentFile();
        if (parent != null && isInside(navigationRoot, parent)) navigate(parent, navigationRoot);
    }

    private Intent resultMetadata() {
        Intent data = new Intent().setPackage(getPackageName()).putExtra(EXTRA_MODE, mode)
                .putExtra(EXTRA_REQUEST_ID, requestId).putExtra(EXTRA_ALLOW_MULTIPLE, multiple);
        ComponentName caller = getCallingActivity();
        // Explicit same-package component prevents FileUriExposedException on API 24+ results.
        if (caller != null && getPackageName().equals(caller.getPackageName())) data.setComponent(caller);
        return data;
    }

    private void finishSelection() {
        if (loading || confirming || !readableDirectory || (mode != MODE_DIRECTORY && selected.isEmpty())) return;
        if (!getPackageName().equals(getCallingPackage())) {
            Ui.message(this, "无法返回文件", "请从金甲虫ADB助手内的上传、下载或安装入口打开选择器。"); return;
        }
        final ArrayList<String> paths = mode == MODE_DIRECTORY ? new ArrayList<>() : new ArrayList<>(selected);
        final ArrayList<File> allowedRoots = new ArrayList<>(roots);
        final Map<String, String> expectedFiles = new HashMap<>(displayedFileTokens);
        final File selectedDirectory = directory;
        final String expectedDirectory = directoryIdentity;
        final int pickerMode = mode, generation = ++confirmationGeneration;
        if (pickerMode == MODE_DIRECTORY) paths.add(selectedDirectory.getAbsolutePath());
        confirming = true; updateControls();
        confirmationTask = reader.submit(() -> {
            final ArrayList<String> tokens = new ArrayList<>();
            String error = null;
            try {
                for (String path : paths) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("选择已取消");
                    File selectedFile = new File(path);
                    // Validate exact spelling against canonical scope without retargeting this path.
                    LocalFileBrowserModel.validateSelection(selectedFile, allowedRoots, pickerMode);
                    String token;
                    if (pickerMode == MODE_DIRECTORY) {
                        token = Compatibility.directoryToken(selectedFile);
                        if (expectedDirectory == null || !expectedDirectory.equals(token))
                            throw new IOException("原目录已被替换或移动，请重新选择存储位置");
                    } else {
                        token = Compatibility.sourceToken(selectedFile);
                        if (!token.equals(expectedFiles.get(path)))
                            throw new IOException("所选文件在显示后发生变化，请重新选择：" + selectedFile.getName());
                    }
                    tokens.add(token);
                }
                if (selectionWeight(paths) + selectionWeight(tokens) > MAX_RESULT_WEIGHT)
                    throw new IOException("所选路径和校验信息过多，请分批选择");
            } catch (IOException | SecurityException failure) { error = failure.getMessage(); }
            final String failure = error;
            main.post(() -> {
                if (destroyed || isFinishing() || generation != confirmationGeneration || !confirming) return;
                confirming = false;
                if (failure != null) {
                    Ui.message(this, "所选项目不可用", failure); reload(false); return;
                }
                returnSelection(paths, tokens, selectedDirectory);
            });
        });
    }

    private void returnSelection(ArrayList<String> paths, ArrayList<String> tokens, File selectedDirectory) {
        if (paths.isEmpty() || paths.size() != tokens.size()) return;
        try {
            Intent data = resultMetadata().putStringArrayListExtra(EXTRA_PATHS, paths)
                    .putStringArrayListExtra(EXTRA_SOURCE_TOKENS, tokens);
            Uri first = Uri.fromFile(new File(paths.get(0)));
            data.setData(first);
            if (mode == MODE_DIRECTORY) data.putExtra(EXTRA_DIRECTORY, paths.get(0));
            else if (paths.size() > 1) {
                ClipData clip = ClipData.newRawUri("本机文件", first);
                for (int i = 1; i < paths.size(); i++) clip.addItem(new ClipData.Item(Uri.fromFile(new File(paths.get(i)))));
                data.setClipData(clip);
            }
            // Deliberately no FLAG_GRANT_*: file URIs must never be forwarded to another app.
            prefs.edit().putString(lastDirectoryKey(), selectedDirectory.getAbsolutePath()).apply();
            setResult(RESULT_OK, data); finish();
        } catch (SecurityException error) {
            Ui.message(this, "所选项目不可用", error.getMessage()); reload(false);
        }
    }

    private void stopConfirmation() {
        if (!confirming) return;
        confirming = false; confirmationGeneration++;
        if (confirmationTask != null) confirmationTask.cancel(true);
        updateControls();
    }

    private void useSaf() {
        stopConfirmation();
        if (mode == MODE_DIRECTORY && Build.VERSION.SDK_INT < 21) {
            Ui.message(this, "此Android版本不支持系统目录选择", "Android 4.4只能通过系统选择单个文件。请选择可写的本机目录；若没有可写目录，请先检查存储设备和权限。");
            return;
        }
        setResult(RESULT_USE_SAF, resultMetadata()); finish();
    }

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) return ModernStorage.hasAllFiles();
        if (Build.VERSION.SDK_INT >= 23) return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED && (mode != MODE_DIRECTORY
                || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED);
        return true;
    }

    private String permissionState() { return Build.VERSION.SDK_INT + ":" + hasStorageAccess(); }

    private String accessDescription() {
        if (Build.VERSION.SDK_INT >= 30) return hasStorageAccess()
                ? "已允许共享存储文件管理；系统仍会限制其他应用的私有目录。"
                : "Android限制直接浏览的范围。可用系统选择器选取所需文件，或主动申请共享存储管理权限。";
        return hasStorageAccess() ? "仅显示本应用能访问的存储。文件夹优先，长按文件夹查看详情。"
                : "尚未获得共享存储权限。可主动授权，或使用系统选择器。";
    }

    private void requestStorage() {
        if (confirming) return;
        if (Build.VERSION.SDK_INT >= 30) {
            if (hasStorageAccess()) {
                Ui.message(this, "共享存储访问", "已获得共享存储管理权限。该权限可在系统设置中撤销；其他应用的私有目录仍受系统限制。也可以随时使用系统选择器。"); return;
            }
            new AlertDialog.Builder(this).setTitle("允许管理共享存储？")
                    .setMessage("此可选权限允许金甲虫ADB助手浏览共享存储中的文件，并向你选择的目录保存下载内容。范围比系统选择器更广，不包含其他应用的私有数据。\n\n仅为本机文件管理使用，不会自动传输或修改文件。你可以在系统设置中随时撤销，也可以改用系统选择器，只授权所选项目。")
                    .setNegativeButton("取消", null).setNeutralButton("系统选择器", (d, w) -> useSaf())
                    .setPositiveButton("前往系统设置", (d, w) -> openAllFilesSettings()).show();
        } else if (Build.VERSION.SDK_INT >= 23 && !hasStorageAccess()) {
            boolean readBlocked = prefs.getBoolean("local_read_requested", false)
                    && !shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE)
                    && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED;
            boolean writeBlocked = mode == MODE_DIRECTORY && prefs.getBoolean("local_write_requested", false)
                    && !shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED;
            boolean blocked = readBlocked || writeBlocked;
            new AlertDialog.Builder(this).setTitle("本机存储权限")
                    .setMessage(mode == MODE_DIRECTORY ? "保存下载需要读取和写入你选择的共享存储目录。此步骤不会传输或修改文件；也可改用系统选择器。"
                            : "浏览并选择共享存储文件需要读取权限。此步骤不会上传文件；也可改用系统选择器。")
                    .setNegativeButton("取消", null).setNeutralButton("系统选择器", (d, w) -> useSaf())
                    .setPositiveButton(blocked ? "打开应用设置" : "请求权限", (d, w) -> {
                        if (blocked) openApplicationSettings();
                        else {
                            SharedPreferences.Editor editor = prefs.edit().putBoolean("local_read_requested", true);
                            if (mode == MODE_DIRECTORY) editor.putBoolean("local_write_requested", true);
                            editor.apply();
                            requestPermissions(mode == MODE_DIRECTORY
                                    ? new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}
                                    : new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, STORAGE_PERMISSION);
                        }
                    }).show();
        } else Ui.message(this, "存储权限", "本应用可以尝试读取可访问的共享存储。只读卷、其他应用私有目录和系统限制仍可能阻止访问，可改用系统选择器。");
    }

    private void openAllFilesSettings() {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            settingsOpened = true;
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())), STORAGE_SETTINGS);
        } catch (android.content.ActivityNotFoundException | SecurityException error) {
            settingsOpened = false;
            Ui.message(this, "此设备没有专用授权页面", "请使用系统选择器，或在系统设置的应用权限页面管理存储访问。");
        }
    }

    private void openApplicationSettings() {
        try {
            settingsOpened = true;
            startActivityForResult(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())), STORAGE_SETTINGS);
        } catch (android.content.ActivityNotFoundException | SecurityException error) {
            settingsOpened = false;
            Ui.message(this, "无法打开设置", "请使用系统选择器，或手动打开系统的应用权限设置。");
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != STORAGE_PERMISSION) return;
        permissionSnapshot = permissionState(); discoverRoots(); reload(false);
        if (!hasStorageAccess()) Toast.makeText(this, "未获得所需存储权限，可重试授权或使用系统选择器。", Toast.LENGTH_LONG).show();
    }

    @Override protected void onResume() {
        super.onResume();
        if (prefs == null || adapter == null) return;
        String now = permissionState();
        if (settingsOpened || !now.equals(permissionSnapshot)) {
            settingsOpened = false; permissionSnapshot = now;
            discoverRoots(); reload(false);
        }
    }

    @Override public void onBackPressed() {
        if (confirming) { stopConfirmation(); return; }
        if (!selected.isEmpty()) {
            selected.clear(); adapter.notifyDataSetChanged(); updateControls(); return;
        }
        if (directory != null && navigationRoot != null && !directory.equals(navigationRoot)) goUp();
        else cancel();
    }

    private void cancel() { stopConfirmation(); setResult(RESULT_CANCELED, resultMetadata()); finish(); }

    @Override protected void onSaveInstanceState(Bundle saved) {
        super.onSaveInstanceState(saved);
        saved.putString(EXTRA_REQUEST_ID, requestId);
        if (directory != null) saved.putString("directory", directory.getAbsolutePath());
        if (navigationRoot != null) saved.putString("navigation_root", navigationRoot.getAbsolutePath());
        saved.putStringArrayList("selected", new ArrayList<>(selected));
        ArrayList<String> tokens = new ArrayList<>();
        for (String path : selected) tokens.add(displayedFileTokens.get(path));
        saved.putStringArrayList(EXTRA_SOURCE_TOKENS, tokens);
        saved.putString("directory_identity", directoryIdentity);
        saved.putInt("scroll", list == null ? 0 : list.getFirstVisiblePosition());
    }

    @Override protected void onDestroy() {
        destroyed = true; loadGeneration++; confirmationGeneration++;
        if (loadTask != null) loadTask.cancel(true);
        if (confirmationTask != null) confirmationTask.cancel(true);
        reader.shutdownNow(); main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void showDetails(LocalFileBrowserModel.Entry entry) {
        Ui.message(this, entry.name, entry.path + "\n" + (entry.directory ? "文件夹" : bytes(entry.size))
                + "\n修改时间：" + modified(entry.modified)
                + "\n" + (entry.file.canRead() ? "可读" : "不可读") + " · " + (entry.file.canWrite() ? "可写" : "不可写"));
    }

    private static String modified(long value) {
        return value > 0 ? DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(value)) : "时间未知";
    }

    private static String bytes(long size) {
        if (size < 1024) return Math.max(0, size) + " B";
        String[] units = {"KB", "MB", "GB", "TB", "PB", "EB"};
        double value = size; int unit = -1;
        do { value /= 1024d; unit++; } while (value >= 1024d && unit < units.length - 1);
        return String.format(Locale.getDefault(), value >= 100 ? "%.0f %s" : "%.1f %s", value, units[unit]);
    }

    /** Keep post-API-19 platform calls in an SDK-gated class. */
    private static final class ModernStorage {
        static boolean hasAllFiles() { return Environment.isExternalStorageManager(); }
    }

    private final class LocalAdapter extends BaseAdapter {
        @Override public int getCount() { return entries.size(); }
        @Override public LocalFileBrowserModel.Entry getItem(int position) { return entries.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View reusable, ViewGroup parent) {
            LocalRow row = reusable instanceof LocalRow ? (LocalRow) reusable : new LocalRow();
            LocalFileBrowserModel.Entry entry = entries.get(position);
            row.checked = selected.contains(entry.path);
            row.selectable = !entry.directory && mode != MODE_DIRECTORY;
            row.name.setText(entry.name);
            row.detail.setText(entry.directory ? "文件夹" : bytes(entry.size));
            row.time.setText(modified(entry.modified));
            row.icon.bind(entry, row.checked);
            row.setBackgroundColor(row.checked ? 0xffe8f0fc : 0xffffffff);
            row.setContentDescription(entry.name + "，" + row.detail.getText() + "，" + row.time.getText()
                    + (row.checked ? "，已选择" : entry.directory ? "，点击进入" : "，点击选择"));
            return row;
        }
    }

    private final class LocalRow extends FrameLayout {
        final LocalIcon icon;
        final TextView name, detail, time;
        boolean checked, selectable;
        LocalRow() {
            super(LocalFilesActivity.this);
            setLayoutParams(new AbsListView.LayoutParams(-1, dp(76)));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            icon = new LocalIcon();
            FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.LEFT | Gravity.CENTER_VERTICAL);
            iconParams.leftMargin = dp(16); addView(icon, iconParams);
            LinearLayout labels = Ui.col(LocalFilesActivity.this);
            name = label("", 18, INK); name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(name, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout metadata = Ui.row(LocalFilesActivity.this);
            detail = label("", 13, MUTED); detail.setSingleLine(true);
            metadata.addView(detail, new LinearLayout.LayoutParams(-2, -2));
            time = label("", 13, MUTED); time.setGravity(Gravity.RIGHT); time.setSingleLine(true);
            time.setEllipsize(TextUtils.TruncateAt.START);
            metadata.addView(time, new LinearLayout.LayoutParams(0, -2, 1));
            labels.addView(metadata);
            FrameLayout.LayoutParams textParams = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL);
            textParams.leftMargin = dp(74); textParams.rightMargin = dp(16); addView(labels, textParams);
            name.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            detail.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            time.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            View line = new View(LocalFilesActivity.this); line.setBackgroundColor(0xffdedede);
            FrameLayout.LayoutParams lineParams = new FrameLayout.LayoutParams(-1, Math.max(1, dp(.75f)), Gravity.BOTTOM);
            lineParams.leftMargin = lineParams.rightMargin = dp(16); addView(line, lineParams);
        }
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info); info.setCheckable(selectable); info.setChecked(checked);
        }
    }

    /** Geometric artwork drawn here; no reference application assets. */
    private final class LocalIcon extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private boolean folder, checked;
        private String extension = "";
        LocalIcon() { super(LocalFilesActivity.this); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        void bind(LocalFileBrowserModel.Entry entry, boolean selected) {
            folder = entry.directory; checked = selected; extension = LocalFileBrowserModel.extension(entry.name); invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); canvas.save(); canvas.scale(getWidth() / 42f, getHeight() / 42f);
            paint.setStyle(Paint.Style.FILL);
            if (folder) {
                paint.setColor(0xffffb74d); path.reset(); path.moveTo(4, 9); path.lineTo(17, 9);
                path.lineTo(21, 13); path.lineTo(38, 13); path.lineTo(38, 34); path.lineTo(4, 34); path.close();
                canvas.drawPath(path, paint);
            } else {
                paint.setColor("apk".equals(extension) ? 0xff7cb342 : "img".equals(extension) ? 0xffab78c5 : 0xff8ba7ba);
                path.reset(); path.moveTo(9, 4); path.lineTo(26, 4); path.lineTo(34, 12);
                path.lineTo(34, 38); path.lineTo(9, 38); path.close(); canvas.drawPath(path, paint);
                paint.setColor(0x88ffffff); path.reset(); path.moveTo(26, 4); path.lineTo(26, 12);
                path.lineTo(34, 12); path.close(); canvas.drawPath(path, paint);
                paint.setColor(0xffffffff); paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(9);
                canvas.drawText(extension.isEmpty() ? "FILE" : extension.substring(0, Math.min(4, extension.length())).toUpperCase(Locale.ROOT), 21.5f, 28, paint);
            }
            if (checked) {
                paint.setColor(BLUE); canvas.drawCircle(32, 32, 9, paint);
                paint.setColor(0xffffffff); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2);
                path.reset(); path.moveTo(27, 32); path.lineTo(31, 36); path.lineTo(37, 28); canvas.drawPath(path, paint);
            }
            canvas.restore();
        }
    }
}
