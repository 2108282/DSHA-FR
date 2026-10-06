package com.deepseekharness.app.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.deepseekharness.app.R;

/**
 * 1:1 像素级复刻 launch-concept.html 中的胶囊滑动进度槽：
 * - 槽高：4dp，全圆角药丸槽（border-radius: 999px），背景色 var(--raised)
 * - 滑块：宽度 38%，全圆角药丸（border-radius: 999px），颜色 var(--primary)
 * - 动效：@keyframes slide { 0%{margin-left:-40%} 100%{margin-left:100%} } (1.4s ease infinite)
 * - 容器内溢出裁切（overflow: hidden）与全自动生命周期启停（节电）
 */
public class CapsuleProgressView extends View {

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bgRect = new RectF();
    private final RectF thumbRect = new RectF();
    private final Path clipPath = new Path();

    private float progress = -0.4f; // 对应 CSS 0%{margin-left:-40%}
    private ValueAnimator animator;

    public CapsuleProgressView(Context context) {
        this(context, null);
    }

    public CapsuleProgressView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CapsuleProgressView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        int colorRaised = ContextCompat.getColor(getContext(), R.color.raised);
        int colorPrimary = ContextCompat.getColor(getContext(), R.color.primary);

        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setColor(colorRaised);

        thumbPaint.setStyle(Paint.Style.FILL);
        thumbPaint.setColor(colorPrimary);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // 高度固定 4dp，宽度随父容器 match_parent
        int h = Math.round(dp(4f));
        int w = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        bgRect.set(0, 0, w, h);
        clipPath.reset();
        float radius = h / 2f;
        clipPath.addRoundRect(bgRect, radius, radius, Path.Direction.CW);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float radius = h / 2f;

        canvas.save();
        // overflow: hidden 保证滑块在移出两端时被完美裁切
        canvas.clipPath(clipPath);

        // 1. 绘制背景圆角槽
        canvas.drawRoundRect(bgRect, radius, radius, bgPaint);

        // 2. 绘制 38% 宽度的滑动胶囊块
        float thumbWidth = w * 0.38f;
        float left = w * progress;
        float right = left + thumbWidth;
        thumbRect.set(left, 0, right, h);
        canvas.drawRoundRect(thumbRect, radius, radius, thumbPaint);

        canvas.restore();
    }

    public void start() {
        if (animator == null) {
            animator = ValueAnimator.ofFloat(-0.4f, 1.0f);
            animator.setDuration(1400); // 1.4s 循环周期
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setInterpolator(new AccelerateDecelerateInterpolator()); // ease 效果
            animator.addUpdateListener(a -> {
                progress = (float) a.getAnimatedValue();
                invalidate();
            });
        }
        if (!animator.isRunning()) {
            animator.start();
        }
    }

    public void stop() {
        if (animator != null && animator.isRunning()) {
            animator.cancel();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getVisibility() == VISIBLE) {
            start();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stop();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) {
            if (isAttachedToWindow()) start();
        } else {
            stop();
        }
    }
}
