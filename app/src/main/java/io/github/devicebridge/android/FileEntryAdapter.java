package io.github.devicebridge.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.github.devicebridge.core.SyncClient;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** File rows with independently drawn icons and caller-owned, path-keyed selection. */
public final class FileEntryAdapter extends BaseAdapter {
    private final Context context;
    private final List<SyncClient.Entry> entries;
    private final Set<String> selected;
    private final SimpleDateFormat timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    /** Keep the supplied list/set and call notifyDataSetChanged after modifying either. */
    public FileEntryAdapter(Context context, List<SyncClient.Entry> entries, Set<String> selected) {
        if (context == null || entries == null) throw new IllegalArgumentException("Context and entries are required");
        this.context = context;
        this.entries = entries;
        this.selected = selected == null ? Collections.<String>emptySet() : selected;
    }

    @Override public int getCount() { return entries.size(); }
    @Override public SyncClient.Entry getItem(int position) { return entries.get(position); }
    @Override public long getItemId(int position) { return position; }
    public void selectionChanged() { notifyDataSetChanged(); }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        FileRow row = convertView instanceof FileRow ? (FileRow) convertView : new FileRow(context);
        SyncClient.Entry entry = getItem(position);
        boolean checked = selected.contains(entry.path);
        String detail = entry.isDirectory ? "文件夹" : entry.isSymbolicLink() ? "链接" : humanSize(entry.size);
        String modified = entry.mtime > 0 ? timestamp.format(new Date(entry.mtime * 1000L)) : "时间未知";
        row.name.setText(entry.name);
        row.detail.setText(detail);
        row.modified.setText(modified);
        row.checked = checked;
        row.setBackgroundColor(checked ? 0xffe8f0fc : 0xffffffff);
        row.icon.bind(entry, checked);
        row.setContentDescription(entry.name + "，" + detail + "，" + modified + (checked ? "，已选择" : ""));
        return row;
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return Math.max(0, bytes) + " B";
        String[] units = {"KB", "MB", "GB", "TB", "PB", "EB"};
        double amount = bytes;
        int unit = -1;
        do { amount /= 1024d; unit++; } while (amount >= 1024 && unit < units.length - 1);
        return String.format(Locale.getDefault(), amount >= 100 ? "%.0f %s" : "%.1f %s", amount, units[unit]);
    }

    private static int dp(Context context, float value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }

    private static TextView text(Context context, int size, int color) {
        TextView view = new TextView(context);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    private static final class FileRow extends FrameLayout {
        final FileIcon icon;
        final TextView name, detail, modified;
        boolean checked;

        FileRow(Context context) {
            super(context);
            // The reference has 75.5dp rows, with an inset 1dp separator.
            setLayoutParams(new AbsListView.LayoutParams(LayoutParams.MATCH_PARENT, dp(context, 75.5f)));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            icon = new FileIcon(context);
            LayoutParams iconParams = new LayoutParams(dp(context, 42), dp(context, 42), Gravity.LEFT | Gravity.CENTER_VERTICAL);
            iconParams.leftMargin = dp(context, 16);
            addView(icon, iconParams);

            LinearLayout labels = new LinearLayout(context);
            labels.setOrientation(LinearLayout.VERTICAL);
            name = text(context, 18, 0xff000000);
            labels.addView(name, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            LinearLayout metadata = new LinearLayout(context);
            metadata.setOrientation(LinearLayout.HORIZONTAL);
            metadata.setGravity(Gravity.CENTER_VERTICAL);
            detail = text(context, 14, 0xff757575);
            metadata.addView(detail, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            modified = text(context, 14, 0xff757575);
            modified.setGravity(Gravity.RIGHT);
            metadata.addView(modified, new LinearLayout.LayoutParams(dp(context, 172), LayoutParams.WRAP_CONTENT));
            labels.addView(metadata, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            LayoutParams textParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL);
            textParams.leftMargin = dp(context, 74);
            textParams.rightMargin = dp(context, 16);
            addView(labels, textParams);

            View divider = new View(context);
            divider.setBackgroundColor(0xffdedede);
            LayoutParams line = new LayoutParams(LayoutParams.MATCH_PARENT, dp(context, 1), Gravity.BOTTOM);
            line.leftMargin = line.rightMargin = dp(context, 16);
            addView(divider, line);
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setCheckable(true);
            info.setChecked(checked);
        }
    }

    /** All artwork is geometric and original; no reference-app resources are used. */
    private static final class FileIcon extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path shape = new Path();
        private String kind = "file";
        private boolean checked;

        FileIcon(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void bind(SyncClient.Entry entry, boolean checked) {
            this.checked = checked;
            String name = entry.name.toLowerCase(Locale.ROOT);
            String extension = name.substring(name.lastIndexOf('.') + 1);
            if (entry.isDirectory) kind = "folder";
            else if (entry.isSymbolicLink()) kind = "link";
            else if (" jpg jpeg png gif webp bmp heic svg ".contains(" " + extension + " ")) kind = "image";
            else if (" mp3 m4a ogg wav flac aac ".contains(" " + extension + " ")) kind = "audio";
            else if (" mp4 mkv webm avi mov m4v ".contains(" " + extension + " ")) kind = "video";
            else if (" zip rar 7z tar gz xz apk apks xapk ".contains(" " + extension + " ")) kind = "archive";
            else kind = "pdf".equals(extension) ? "pdf" : "file";
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save();
            canvas.scale(getWidth() / 42f, getHeight() / 42f);
            paint.setStyle(Paint.Style.FILL);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            if ("folder".equals(kind)) {
                paint.setColor(0xffffb74d);
                shape.reset();
                shape.moveTo(7, 7); shape.lineTo(15, 7); shape.quadTo(17, 7, 21, 10.5f);
                shape.lineTo(34, 10.5f); shape.quadTo(38.5f, 10.5f, 38.5f, 15);
                shape.lineTo(38.5f, 31); shape.quadTo(38.5f, 35, 34.5f, 35);
                shape.lineTo(7.5f, 35); shape.quadTo(3.5f, 35, 3.5f, 31);
                shape.lineTo(3.5f, 11.5f); shape.quadTo(3.5f, 7, 7, 7); shape.close();
                canvas.drawPath(shape, paint);
            } else {
                int color = "image".equals(kind) ? 0xff66bb6a : "audio".equals(kind) ? 0xffab78c5
                        : "video".equals(kind) ? 0xffed8295 : "archive".equals(kind) ? 0xffba9b6c
                        : "pdf".equals(kind) ? 0xffed786b : 0xff8ba7ba;
                paint.setColor(color);
                shape.reset(); shape.moveTo(9, 3); shape.lineTo(26, 3); shape.lineTo(34, 11);
                shape.lineTo(34, 37); shape.quadTo(34, 39, 32, 39); shape.lineTo(10, 39);
                shape.quadTo(8, 39, 8, 37); shape.lineTo(8, 5); shape.quadTo(8, 3, 9, 3); shape.close();
                canvas.drawPath(shape, paint);
                paint.setColor(0x77ffffff);
                shape.reset(); shape.moveTo(26, 3); shape.lineTo(26, 11); shape.lineTo(34, 11); shape.close();
                canvas.drawPath(shape, paint);
                paint.setColor(0xffffffff);
                paint.setStrokeWidth(1.8f);
                if ("image".equals(kind)) {
                    canvas.drawCircle(16, 19, 2.2f, paint);
                    shape.reset(); shape.moveTo(12, 31); shape.lineTo(20, 23); shape.lineTo(24, 27);
                    shape.lineTo(27, 23); shape.lineTo(31, 31); shape.close(); canvas.drawPath(shape, paint);
                } else if ("video".equals(kind)) {
                    shape.reset(); shape.moveTo(17, 18); shape.lineTo(28, 25); shape.lineTo(17, 32);
                    shape.close(); canvas.drawPath(shape, paint);
                } else if ("audio".equals(kind)) {
                    paint.setStyle(Paint.Style.STROKE);
                    canvas.drawLine(25, 17, 25, 29, paint); canvas.drawLine(25, 17, 18, 19, paint);
                    canvas.drawLine(18, 19, 18, 31, paint); paint.setStyle(Paint.Style.FILL);
                    canvas.drawOval(new RectF(13, 29, 19, 33), paint); canvas.drawOval(new RectF(20, 27, 26, 31), paint);
                } else if ("archive".equals(kind)) {
                    for (int i = 0; i < 6; i++) canvas.drawRect(19 + i % 2 * 2, 15 + i * 2.5f, 21 + i % 2 * 2, 17 + i * 2.5f, paint);
                } else if ("link".equals(kind)) {
                    paint.setStyle(Paint.Style.STROKE);
                    canvas.drawRoundRect(new RectF(12, 23, 23, 29), 3, 3, paint);
                    canvas.drawRoundRect(new RectF(20, 18, 31, 24), 3, 3, paint);
                    canvas.drawLine(18, 26, 25, 21, paint);
                } else {
                    paint.setStyle(Paint.Style.STROKE);
                    for (int i = 0; i < 3; i++) canvas.drawLine(13, 20 + i * 5, 28 - i * 2, 20 + i * 5, paint);
                }
            }
            if (checked) {
                paint.setStyle(Paint.Style.FILL); paint.setColor(0xff5b93e5);
                canvas.drawCircle(33, 33, 8.5f, paint);
                paint.setStyle(Paint.Style.STROKE); paint.setColor(0xffffffff); paint.setStrokeWidth(2.1f);
                shape.reset(); shape.moveTo(29, 33); shape.lineTo(32, 36); shape.lineTo(37, 30);
                canvas.drawPath(shape, paint);
            }
            canvas.restore();
        }
    }
}
