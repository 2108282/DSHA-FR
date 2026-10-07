package com.deepseekharness.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.deepseekharness.app.R;

/**
 * 1:1 像素级纯 Skia 自绘胶囊状态徽章 (.badge):
 * - 尺寸：高度 20dp，圆角 999dp，左右内衬 8dp
 * - 文字：11sp 粗体，垂直水平绝对居中
 * - 启用态：背景 ok_bg (#E6F6EE)，文字 ok (#2F9E64)
 * - 禁用态：背景 raised (#F0F2F5)，文字 text_muted (#7A8494)
 */
public class ModernBadgeView extends View {

    private String text = "已启用";
    private boolean isEnabledState = true;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private int colorOkBg;
    private int colorOk;
    private int colorRaised;
    private int colorMuted;
    private float density;

    public ModernBadgeView(Context context) {
        this(context, null);
    }

    public ModernBadgeView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ModernBadgeView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        density = getResources().getDisplayMetrics().density;

        colorOkBg = ContextCompat.getColor(getContext(), R.color.ok_bg);
        colorOk = ContextCompat.getColor(getContext(), R.color.ok);
        colorRaised = ContextCompat.getColor(getContext(), R.color.raised);
        colorMuted = ContextCompat.getColor(getContext(), R.color.text_muted);

        bgPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(11f * density);
        textPaint.setFakeBoldText(true);

        updateColors();
    }

    private void updateColors() {
        bgPaint.setColor(isEnabledState ? colorOkBg : colorRaised);
        textPaint.setColor(isEnabledState ? colorOk : colorMuted);
    }

    public void setBadge(boolean isEnabled, String text) {
        this.isEnabledState = isEnabled;
        this.text = text != null ? text : "";
        updateColors();
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int h = Math.round(20f * density);
        float textWidth = textPaint.measureText(text);
        int w = Math.round(textWidth + 16f * density); // 左右各 8dp padding
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        rect.set(0, 0, w, h);
        float radius = h / 2f;
        canvas.drawRoundRect(rect, radius, radius, bgPaint);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = h / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(text, w / 2f, baseline, textPaint);
    }
}
