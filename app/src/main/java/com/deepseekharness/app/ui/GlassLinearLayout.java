package com.deepseekharness.app.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.deepseekharness.app.R;

/**
 * 1:1 像素级复刻 launch-concept.html 中的毛玻璃卡片 (.gcard / .appbar):
 * - 背景模糊：backdrop-filter: blur(16px) saturate(1.15) (通过 Android 12+ 原生 RenderEffect 硬件加速)
 * - 蒙层颜色：var(--card-glass): rgba(255,255,255,.82) / 深色 rgba(22,27,36,.88)
 * - 边框描边：var(--glass-border): 1px solid rgba(229,231,235,.55)
 * - 圆角裁切：var(--r-xl) = 24dp (卡片 1) / var(--r-lg) = 18dp (卡片 2, 3, 4)
 * - 柔和阴影：box-shadow: 0 4px 16px rgba(26,34,48,.06), 0 1px 3px rgba(26,34,48,.04)
 */
public class GlassLinearLayout extends LinearLayout {

    private static boolean sIsCapturingBackdrop = false;

    private float cornerRadius = 0f;
    private float blurRadius = 16f;

    private RenderNode blurRenderNode;
    private RenderEffect blurEffect;
    private boolean isDirty = true;

    private final Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();
    private final Path strokePath = new Path();
    private final RectF boundsRect = new RectF();
    private final RectF strokeRect = new RectF();

    private View rootBackdropView;

    private final ViewTreeObserver.OnPreDrawListener preDrawListener = () -> {
        if (!isAttachedToWindow() || getVisibility() != VISIBLE) return true;
        if (isDirty) {
            updateBlurBackdrop();
        }
        return true;
    };

    private final ViewTreeObserver.OnScrollChangedListener scrollChangedListener = () -> {
        isDirty = true;
    };

    public GlassLinearLayout(Context context) {
        this(context, null);
    }

    public GlassLinearLayout(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public GlassLinearLayout(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(attrs);
    }

    private void init(@Nullable AttributeSet attrs) {
        float density = getResources().getDisplayMetrics().density;
        cornerRadius = 18f * density;
        blurRadius = 16f * density;

        if (attrs != null) {
            TypedArray a = getContext().obtainStyledAttributes(attrs, R.styleable.GlassLinearLayout);
            cornerRadius = a.getDimension(R.styleable.GlassLinearLayout_glassCornerRadius, cornerRadius);
            blurRadius = a.getDimension(R.styleable.GlassLinearLayout_glassBlurRadius, blurRadius);
            a.recycle();
        }

        int overlayColor = ContextCompat.getColor(getContext(), R.color.card_glass);
        int strokeColor = ContextCompat.getColor(getContext(), R.color.line_soft);

        overlayPaint.setStyle(Paint.Style.FILL);
        overlayPaint.setColor(overlayColor);

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1f * density);
        strokePaint.setColor(strokeColor);

        // 设置 OutlineProvider 投射与 HTML 一致的柔和立体阴影
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius);
            }
        });
        setClipToOutline(false);

        if (Build.VERSION.SDK_INT >= 31) {
            try {
                blurRenderNode = new RenderNode("GlassBlur_" + hashCode());
                blurEffect = RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP);
                blurRenderNode.setRenderEffect(blurEffect);
            } catch (Throwable ignored) {
                blurRenderNode = null;
            }
        }
    }

    public void setCornerRadius(float radiusDp) {
        this.cornerRadius = radiusDp * getResources().getDisplayMetrics().density;
        updatePaths();
        invalidateOutline();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updatePaths();
        isDirty = true;
    }

    private void updatePaths() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        boundsRect.set(0, 0, w, h);
        clipPath.reset();
        clipPath.addRoundRect(boundsRect, cornerRadius, cornerRadius, Path.Direction.CW);

        float halfStroke = strokePaint.getStrokeWidth() / 2f;
        strokeRect.set(halfStroke, halfStroke, w - halfStroke, h - halfStroke);
        strokePath.reset();
        float strokeRadius = Math.max(0, cornerRadius - halfStroke);
        strokePath.addRoundRect(strokeRect, strokeRadius, strokeRadius, Path.Direction.CW);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (rootBackdropView == null) {
            Activity act = getActivity();
            if (act != null) {
                rootBackdropView = act.getWindow().getDecorView().findViewById(android.R.id.content);
            }
            if (rootBackdropView == null) {
                rootBackdropView = getRootView();
            }
        }
        getViewTreeObserver().addOnPreDrawListener(preDrawListener);
        getViewTreeObserver().addOnScrollChangedListener(scrollChangedListener);
        isDirty = true;
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(preDrawListener);
        getViewTreeObserver().removeOnScrollChangedListener(scrollChangedListener);
        if (blurRenderNode != null) {
            blurRenderNode.discardDisplayList();
        }
        super.onDetachedFromWindow();
    }

    private Activity getActivity() {
        Context ctx = getContext();
        while (ctx instanceof android.content.ContextWrapper) {
            if (ctx instanceof Activity) return (Activity) ctx;
            ctx = ((android.content.ContextWrapper) ctx).getBaseContext();
        }
        return null;
    }

    private void updateBlurBackdrop() {
        if (Build.VERSION.SDK_INT < 31 || blurRenderNode == null || rootBackdropView == null) {
            isDirty = false;
            return;
        }
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            isDirty = false;
            return;
        }

        sIsCapturingBackdrop = true;
        try {
            android.graphics.RecordingCanvas canvas = blurRenderNode.beginRecording(w, h);
            int[] rootLoc = new int[2];
            int[] myLoc = new int[2];
            rootBackdropView.getLocationInWindow(rootLoc);
            getLocationInWindow(myLoc);
            int dx = myLoc[0] - rootLoc[0];
            int dy = myLoc[1] - rootLoc[1];
            canvas.translate(-dx, -dy);
            rootBackdropView.draw(canvas);
            blurRenderNode.endRecording();
        } catch (Throwable ignored) {
        } finally {
            sIsCapturingBackdrop = false;
            isDirty = false;
        }
    }

    @Override
    public void draw(Canvas canvas) {
        // 当作为背景被录制时跳过自身，杜绝递归
        if (sIsCapturingBackdrop) return;

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            super.draw(canvas);
            return;
        }

        canvas.save();
        canvas.clipPath(clipPath);

        // 1. 绘制背后实时 16px 硬件加速毛玻璃高斯模糊 (backdrop-filter: blur(16px))
        if (Build.VERSION.SDK_INT >= 31 && blurRenderNode != null && blurRenderNode.hasDisplayList()) {
            canvas.drawRenderNode(blurRenderNode);
        }

        // 2. 覆盖 82% 纯白/深色半透质感 (var(--card-glass))
        canvas.drawRect(0, 0, w, h, overlayPaint);

        // 3. 绘制 1px 精细玻璃边框 (var(--glass-border))
        if (strokePaint.getStrokeWidth() > 0) {
            canvas.drawPath(strokePath, strokePaint);
        }

        canvas.restore();

        // 4. 绘制内部子组件
        super.draw(canvas);
    }
}
