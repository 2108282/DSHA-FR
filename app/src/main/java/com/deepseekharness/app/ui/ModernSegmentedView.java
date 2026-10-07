package com.deepseekharness.app.ui;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.deepseekharness.app.R;

/**
 * 1:1 像素级纯 Skia 自绘分段控制器 (.seg):
 * - 外槽：圆角 18dp，内衬 4dp，背景 raised_glass，1dp 细玻璃边框
 * - 选中滑块：圆角 14dp，纯白卡片底，Skia 真实微阴影 0 2px 6px rgba(26,34,48,.08)
 * - 180ms cubic-bezier(.2,.8,.4,1) 平滑滑块位移与双色文字实时平滑插值
 * - 纯 Skia 渲染，零 XML 干扰！
 */
public class ModernSegmentedView extends View {

    public interface OnTabSelectedListener {
        void onTabSelected(int index);
    }

    private final String[] tabs = {"插件市场", "插件管理"};
    private int selectedIndex = 1; // 默认插件管理
    private float progress = 1f; // 0f = 市场, 1f = 管理
    private ValueAnimator animator;
    private OnTabSelectedListener listener;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    private final RectF bgRect = new RectF();
    private final RectF strokeRect = new RectF();
    private final RectF thumbRect = new RectF();
    private final ArgbEvaluator argbEvaluator = new ArgbEvaluator();

    private Bitmap thumbShadowBitmap = null;
    private float density;
    private float pad;
    private float gap;
    private float outerRadius;
    private float innerRadius;

    private int colorPrimary;
    private int colorSecondary;
    private int colorCard;

    public ModernSegmentedView(Context context) {
        this(context, null);
    }

    public ModernSegmentedView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ModernSegmentedView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        density = getResources().getDisplayMetrics().density;
        pad = 4f * density;
        gap = 8f * density;
        outerRadius = 18f * density;
        innerRadius = 14f * density;

        int colorRaisedGlass = ContextCompat.getColor(getContext(), R.color.raised_glass);
        int colorLineSoft = ContextCompat.getColor(getContext(), R.color.line_soft);
        colorCard = ContextCompat.getColor(getContext(), R.color.card);
        colorPrimary = ContextCompat.getColor(getContext(), R.color.primary);
        colorSecondary = ContextCompat.getColor(getContext(), R.color.text_secondary);

        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setColor(colorRaisedGlass);

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1f * density);
        strokePaint.setColor(colorLineSoft);

        thumbPaint.setStyle(Paint.Style.FILL);
        thumbPaint.setColor(colorCard);

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(15f * density);
        textPaint.setFakeBoldText(true);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int h = Math.round(50f * density);
        int w = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) return;

        bgRect.set(0, 0, w, h);
        float halfStroke = strokePaint.getStrokeWidth() / 2f;
        strokeRect.set(halfStroke, halfStroke, w - halfStroke, h - halfStroke);

        float innerW = w - pad * 2f;
        float innerH = h - pad * 2f;
        float tabW = (innerW - gap) / 2f;

        // 构建滑块的 Skia 微阴影离屏缓存 (0 2px 6px rgba(26,34,48,.08))
        buildThumbShadowCache(tabW, innerH);
    }

    private void buildThumbShadowCache(float tw, float th) {
        if (thumbShadowBitmap != null && !thumbShadowBitmap.isRecycled()) {
            thumbShadowBitmap.recycle();
            thumbShadowBitmap = null;
        }

        int shadowPad = Math.round(10f * density);
        int bw = Math.round(tw) + shadowPad * 2;
        int bh = Math.round(th) + shadowPad * 2;
        if (bw <= 0 || bh <= 0) return;

        try {
            thumbShadowBitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(thumbShadowBitmap);

            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.FILL);
            boolean isDark = ThemeController.isDark(getContext());
            p.setColor(isDark ? 0x4D000000 : 0x141A2230);
            p.setMaskFilter(new BlurMaskFilter(6f * density, BlurMaskFilter.Blur.NORMAL));

            float offsetY = 2f * density;
            RectF r = new RectF(shadowPad, shadowPad + offsetY, shadowPad + tw, shadowPad + th + offsetY);
            canvas.drawRoundRect(r, innerRadius, innerRadius, p);
        } catch (Throwable ignored) {
            thumbShadowBitmap = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        // 1. 绘制底槽 (raised_glass + 1dp 细线)
        canvas.drawRoundRect(bgRect, outerRadius, outerRadius, bgPaint);
        float innerRadiusOffset = Math.max(0, outerRadius - strokePaint.getStrokeWidth() / 2f);
        canvas.drawRoundRect(strokeRect, innerRadiusOffset, innerRadiusOffset, strokePaint);

        // 2. 绘制移动滑块 Thumb (带 Skia 微阴影)
        float innerH = h - pad * 2f;
        float innerW = w - pad * 2f;
        float tabW = (innerW - gap) / 2f;

        float thumbLeft = pad + progress * (tabW + gap);
        float thumbTop = pad;
        float thumbRight = thumbLeft + tabW;
        float thumbBottom = thumbTop + innerH;
        thumbRect.set(thumbLeft, thumbTop, thumbRight, thumbBottom);

        if (thumbShadowBitmap != null && !thumbShadowBitmap.isRecycled()) {
            float shadowPad = 10f * density;
            canvas.drawBitmap(thumbShadowBitmap, thumbLeft - shadowPad, thumbTop - shadowPad, shadowPaint);
        }
        canvas.drawRoundRect(thumbRect, innerRadius, innerRadius, thumbPaint);

        // 3. 绘制文字 (居中、随进度平滑变色)
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = h / 2f - (fm.ascent + fm.descent) / 2f;

        // 左边 Tab (插件市场)
        float leftCenterX = pad + tabW / 2f;
        int colorLeft = (int) argbEvaluator.evaluate(progress, colorPrimary, colorSecondary);
        textPaint.setColor(colorLeft);
        canvas.drawText(tabs[0], leftCenterX, baseline, textPaint);

        // 右边 Tab (插件管理)
        float rightCenterX = pad + tabW + gap + tabW / 2f;
        int colorRight = (int) argbEvaluator.evaluate(progress, colorSecondary, colorPrimary);
        textPaint.setColor(colorRight);
        canvas.drawText(tabs[1], rightCenterX, baseline, textPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            float x = event.getX();
            int newIndex = x < getWidth() / 2f ? 0 : 1;
            setSelectedIndex(newIndex, true);
        }
        return true;
    }

    public void setSelectedIndex(int index, boolean animate) {
        if (index != 0 && index != 1) return;
        boolean changed = (this.selectedIndex != index);
        this.selectedIndex = index;

        if (animator != null) {
            animator.cancel();
        }

        float target = (index == 0) ? 0f : 1f;
        if (animate && isAttachedToWindow()) {
            animator = ValueAnimator.ofFloat(progress, target);
            animator.setDuration(180);
            animator.setInterpolator(new PathInterpolator(0.2f, 0.8f, 0.4f, 1.0f));
            animator.addUpdateListener(a -> {
                progress = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.start();
        } else {
            progress = target;
            invalidate();
        }

        if (changed && listener != null) {
            listener.onTabSelected(index);
        }
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public void setOnTabSelectedListener(OnTabSelectedListener listener) {
        this.listener = listener;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (thumbShadowBitmap != null && !thumbShadowBitmap.isRecycled()) {
            thumbShadowBitmap.recycle();
            thumbShadowBitmap = null;
        }
    }
}
