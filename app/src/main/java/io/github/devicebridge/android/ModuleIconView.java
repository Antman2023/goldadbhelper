package io.github.devicebridge.android;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/** Independently drawn navigation symbols. No reference application artwork is used. */
public final class ModuleIconView extends View {
    private static final String[] LABELS = {"设备信息", "实用工具", "远程控制", "文件管理", "应用管理", "进程管理", "运行命令"};
    private static final int BLUE = 0xff1976d2, GRAY = 0xff666666;
    private final int index;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ModuleIconView(Context context, int index, boolean selected, Runnable action) {
        super(context);
        if (index < 0 || index >= LABELS.length) throw new IllegalArgumentException("Invalid module index");
        this.index = index;
        setSelected(selected);
        setContentDescription(LABELS[index]);
        setFocusable(true);
        setClickable(true);
        setMinimumWidth(dp(72));
        setMinimumHeight(dp(48));
        setBackground(Compatibility.ripple(0x1f1976d2, null, null));
        setOnClickListener(view -> { if (action != null) action.run(); });
    }

    private int dp(float value) { return Ui.dp(getContext(), value); }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(resolveSize(dp(72), widthMeasureSpec), resolveSize(dp(48), heightMeasureSpec));
    }

    @Override public void setSelected(boolean selected) { super.setSelected(selected); invalidate(); }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.Button");
        info.setSelected(isSelected());
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        paint.setColor(isSelected() ? BLUE : GRAY);
        paint.setStyle(Paint.Style.FILL);
        if (isSelected()) canvas.drawRect(0, getHeight() - dp(2), getWidth(), getHeight(), paint);
        canvas.save();
        canvas.translate(getWidth() / 2f, (getHeight() - dp(2)) / 2f);
        float scale = getResources().getDisplayMetrics().density;
        canvas.scale(scale, scale);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.9f);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        switch (index) {
            case 0:
                canvas.drawCircle(0, 0, 9, paint);
                canvas.drawLine(0, -1, 0, 5, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(0, -5, 1.15f, paint);
                break;
            case 1:
                Path wrench = new Path();
                wrench.moveTo(-5.4f, -10); wrench.lineTo(-1.5f, -6.1f);
                wrench.lineTo(-4.7f, -2.9f); wrench.lineTo(-8.6f, -6.8f);
                wrench.cubicTo(-10.3f, -2.3f, -6.5f, 1.6f, -2.3f, .1f);
                wrench.lineTo(7.3f, 9.7f); wrench.lineTo(10, 7);
                wrench.lineTo(.5f, -2.6f); wrench.cubicTo(2, -7, -1.2f, -10.1f, -5.4f, -10);
                canvas.drawPath(wrench, paint);
                break;
            case 2:
                canvas.drawRoundRect(new RectF(-7, -10, 7, 10), 7, 7, paint);
                canvas.drawLine(-7, -2, 7, -2, paint);
                canvas.drawLine(0, -9, 0, -2, paint);
                break;
            case 3:
                Path folder = new Path();
                folder.moveTo(-9, -7); folder.lineTo(-3, -7); folder.lineTo(0, -4);
                folder.lineTo(9, -4); folder.lineTo(9, 7); folder.lineTo(-9, 7); folder.close();
                canvas.drawPath(folder, paint);
                break;
            case 4:
                paint.setStyle(Paint.Style.FILL);
                for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++)
                    canvas.drawRect(x * 6 - 2, y * 6 - 2, x * 6 + 2, y * 6 + 2, paint);
                break;
            case 5:
                canvas.drawLine(-10, 9, 10, 9, paint);
                canvas.drawRoundRect(new RectF(-8, 0, -5, 7), .6f, .6f, paint);
                canvas.drawRoundRect(new RectF(-1.5f, -7, 1.5f, 7), .6f, .6f, paint);
                canvas.drawRoundRect(new RectF(5, -3, 8, 7), .6f, .6f, paint);
                break;
            case 6:
                canvas.drawRoundRect(new RectF(-10, -8, 10, 8), 1.5f, 1.5f, paint);
                canvas.drawLine(-6, -3, -2, 0, paint); canvas.drawLine(-2, 0, -6, 3, paint);
                canvas.drawLine(1, 3, 6, 3, paint);
                break;
            default: break;
        }
        canvas.restore();
    }
}
