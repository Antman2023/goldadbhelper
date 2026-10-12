package io.github.devicebridge.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.TextUtils;
import android.util.Base64;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

/** Application metadata rows. Accepts the helper's JSONObject rows or equivalent maps. */
public final class AppEntryAdapter extends BaseAdapter {
    private static final int MAX_ICON_TEXT = 256 * 1024;
    private static final int MAX_ICON_DIMENSION = 2048;
    private static final int DECODED_ICON_DIMENSION = 128;
    private final Context context;
    private final List<?> entries;
    private final Set<String> selected;
    // Bounded by bitmap allocation AND retained encoded-text size, rather than app count.
    private final LruCache<String, Bitmap> icons = new LruCache<String, Bitmap>(1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getAllocationByteCount() + key.length() * 2;
        }
    };

    /** Fields: packageName, label, icon (base64 PNG), enabled, system; selected is optional. */
    public AppEntryAdapter(Context context, List<?> entries) {
        this(context, entries, null);
    }

    /** Optional selection is keyed by packageName. Neither constructor mutates the data. */
    public AppEntryAdapter(Context context, List<?> entries, Set<String> selected) {
        if (context == null || entries == null) throw new IllegalArgumentException("Context and entries are required");
        this.context = context;
        this.entries = entries;
        this.selected = selected == null ? Collections.<String>emptySet() : selected;
    }

    @Override public int getCount() { return entries.size(); }
    @Override public Object getItem(int position) { return entries.get(position); }
    @Override public long getItemId(int position) { return position; }
    public void selectionChanged() { notifyDataSetChanged(); }
    /** Safe to call when the screen is discarded; visible bitmap references remain valid. */
    public void clearIconCache() { icons.evictAll(); }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        AppRow row = convertView instanceof AppRow ? (AppRow) convertView : new AppRow(context);
        Object entry = getItem(position);
        String packageName = value(entry, "packageName", "");
        String label = value(entry, "label", packageName);
        if (label.trim().isEmpty()) label = packageName;
        boolean enabled = flag(entry, "enabled", true);
        boolean checked = selected.contains(packageName) || flag(entry, "selected", false);
        String title = label + (enabled ? "" : " [已停用]");
        row.name.setText(title);
        row.name.setTextColor(enabled ? 0xff000000 : 0xff757575);
        row.packageName.setText(value(entry,"detail",packageName));
        row.checked = checked;
        row.setBackgroundColor(checked ? 0xffe8f0fc : 0xffffffff);
        row.icon.bind(decodeIcon(value(entry, "icon", "")), flag(entry, "system", false), checked, enabled);
        row.setContentDescription(title + "，" + packageName + (checked ? "，已选择" : ""));
        return row;
    }

    private static String value(Object entry, String field, String fallback) {
        if (entry instanceof JSONObject) return ((JSONObject) entry).optString(field, fallback);
        if (entry instanceof Map) {
            Object value = ((Map<?, ?>) entry).get(field);
            if (value != null && value != JSONObject.NULL) return String.valueOf(value);
        }
        return fallback;
    }

    private static boolean flag(Object entry, String field, boolean fallback) {
        String value = value(entry, field, "");
        if ("true".equalsIgnoreCase(value) || "1".equals(value)) return true;
        if ("false".equalsIgnoreCase(value) || "0".equals(value)) return false;
        return fallback;
    }

    private Bitmap decodeIcon(String encoded) {
        if (encoded.isEmpty() || encoded.length() > MAX_ICON_TEXT) return null;
        Bitmap cached = icons.get(encoded);
        if (cached != null) return cached;
        try {
            byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                    || bounds.outWidth > MAX_ICON_DIMENSION || bounds.outHeight > MAX_ICON_DIMENSION) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            while (bounds.outWidth / options.inSampleSize > DECODED_ICON_DIMENSION
                    || bounds.outHeight / options.inSampleSize > DECODED_ICON_DIMENSION) options.inSampleSize *= 2;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inScaled = false;
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (bitmap != null) icons.put(encoded, bitmap);
            return bitmap;
        } catch (IllegalArgumentException invalidBase64) {
            return null;
        } catch (OutOfMemoryError lowMemory) {
            icons.evictAll();
            return null;
        }
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

    private static final class AppRow extends FrameLayout {
        final AppIcon icon;
        final TextView name, packageName;
        boolean checked;

        AppRow(Context context) {
            super(context);
            setLayoutParams(new AbsListView.LayoutParams(LayoutParams.MATCH_PARENT, dp(context, 73)));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            icon = new AppIcon(context);
            LayoutParams iconParams = new LayoutParams(dp(context, 42), dp(context, 42), Gravity.LEFT | Gravity.CENTER_VERTICAL);
            iconParams.leftMargin = dp(context, 16);
            addView(icon, iconParams);

            LinearLayout labels = new LinearLayout(context);
            labels.setOrientation(LinearLayout.VERTICAL);
            name = text(context, 18, 0xff000000);
            packageName = text(context, 13, 0xff757575);
            labels.addView(name, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            labels.addView(packageName, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            LayoutParams textParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL);
            textParams.leftMargin = dp(context, 74);
            textParams.rightMargin = dp(context, 8);
            addView(labels, textParams);

            View divider = new View(context);
            divider.setBackgroundColor(0xffdedede);
            addView(divider, new LayoutParams(LayoutParams.MATCH_PARENT, dp(context, 1), Gravity.BOTTOM));
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setSelected(checked);
        }
    }

    /** Remote app icons are used when supplied; fallback artwork is independently drawn. */
    private static final class AppIcon extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Path check = new Path();
        private Bitmap bitmap;
        private boolean system, checked, enabled = true;

        AppIcon(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void bind(Bitmap bitmap, boolean system, boolean checked, boolean enabled) {
            this.bitmap = bitmap;
            this.system = system;
            this.checked = checked;
            this.enabled = enabled;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save();
            canvas.scale(getWidth() / 42f, getHeight() / 42f);
            paint.setStyle(Paint.Style.FILL);
            paint.setAlpha(enabled ? 255 : 140);
            if (bitmap != null) {
                float scale = Math.min(42f / bitmap.getWidth(), 42f / bitmap.getHeight());
                float width = bitmap.getWidth() * scale, height = bitmap.getHeight() * scale;
                canvas.drawBitmap(bitmap, null, new RectF((42 - width) / 2, (42 - height) / 2,
                        (42 + width) / 2, (42 + height) / 2), paint);
            } else {
                paint.setColor(system ? 0xff8bbb48 : 0xff5b93e5);
                paint.setAlpha(enabled ? 255 : 140);
                canvas.drawCircle(21, 21, 20, paint);
                paint.setColor(0xffffffff);
                paint.setAlpha(enabled ? 255 : 140);
                for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++)
                    canvas.drawRoundRect(new RectF(11 + x * 11, 11 + y * 11, 19 + x * 11, 19 + y * 11), 1.8f, 1.8f, paint);
            }
            if (checked) {
                paint.setColor(0xff5b93e5); paint.setAlpha(255);
                canvas.drawCircle(33, 33, 8.5f, paint);
                paint.setColor(0xffffffff); paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2.1f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
                check.reset(); check.moveTo(29, 33); check.lineTo(32, 36); check.lineTo(37, 30);
                canvas.drawPath(check, paint);
            }
            canvas.restore();
        }
    }
}
