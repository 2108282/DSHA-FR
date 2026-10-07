package com.deepseekharness.app.ui;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.PathInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.deepseekharness.app.R;

/**
 * 1:1 像素级复刻 launch-concept.html 中的 .toggle 开关：
 * - 尺寸：44dp x 26dp，药丸圆角 13dp
 * - 滑块：20dp x 20dp 纯白圆球，上下左右 3dp 边距
 * - 阴影：box-shadow: 0 1px 3px rgba(0,0,0,.2)
 * - 缓动：180ms cubic-bezier(.2,.8,.4,1) 平滑位移 18dp
 * - 背景过渡：180ms 浅灰 raised 渐变至 primary 蓝
 */
public class DshaToggle extends View {

    public interface OnCheckedChangeListener {
        void onCheckedChanged(DshaToggle toggle, boolean isChecked);
    }

    private boolean isChecked = false;
    private float progress = 0f; // 0f = off, 1f = on
    private ValueAnimator animator;
    private OnCheckedChangeListener listener;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bgRect = new RectF();
    private final ArgbEvaluator argbEvaluator = new ArgbEvaluator();

    private int colorOff;
    private int colorOn;
    private int colorBorder;

    public DshaToggle(Context context) {
        this(context, null);
    }

    public DshaToggle(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DshaToggle(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setLayerType(LAYER_TYPE_SOFTWARE, null); // 开启软件渲染保证 setShadowLayer 阴影生效

        colorOff = ContextCompat.getColor(getContext(), R.color.raised);
        colorOn = ContextCompat.getColor(getContext(), R.color.primary);
        colorBorder = ContextCompat.getColor(getContext(), R.color.line);

        bgPaint.setStyle(Paint.Style.FILL);

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1f));
        borderPaint.setColor(colorBorder);

        thumbPaint.setStyle(Paint.Style.FILL);
        thumbPaint.setColor(0xFFFFFFFF);
        // 严格对齐 CSS: box-shadow: 0 1px 3px rgba(0,0,0,.2)
        thumbPaint.setShadowLayer(dp(3f), 0, dp(1f), 0x33000000);

        setClickable(true);
        setFocusable(true);
        setOnClickListener(v -> toggle());
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // 固定 44dp x 26dp
        int w = Math.round(dp(44f));
        int h = Math.round(dp(26f));
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        bgRect.set(0, 0, w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float radius = getHeight() / 2f;

        // 1. 绘制背景色渐变（180ms ease 颜色插值）
        int currentBg = (int) argbEvaluator.evaluate(progress, colorOff, colorOn);
        bgPaint.setColor(currentBg);
        canvas.drawRoundRect(bgRect, radius, radius, bgPaint);

        // 2. 未开启时绘制细边线（随进度淡出）
        if (progress < 1f) {
            borderPaint.setAlpha((int) ((1f - progress) * 255));
            RectF strokeRect = new RectF(bgRect);
            float inset = dp(0.5f);
            strokeRect.inset(inset, inset);
            canvas.drawRoundRect(strokeRect, radius, radius, borderPaint);
        }

        // 3. 绘制纯白小圆球 + 立体微阴影
        // 未开启时左侧距边 3dp，圆球直径 20dp（半径 10dp），中心 x 从 (3+10)=13dp 滑动到 (13+18)=31dp
        float startCenterX = dp(3f) + dp(10f);
        float translateX = dp(18f) * progress;
        float cx = startCenterX + translateX;
        float cy = getHeight() / 2f;
        float thumbRadius = dp(10f);

        canvas.drawCircle(cx, cy, thumbRadius, thumbPaint);
    }

    public void toggle() {
        setChecked(!isChecked, true, true);
    }

    public boolean isChecked() {
        return isChecked;
    }

    public void setChecked(boolean checked) {
        setChecked(checked, false, false);
    }

    public void setChecked(boolean checked, boolean animate) {
        setChecked(checked, animate, false);
    }

    public void setChecked(boolean checked, boolean animate, boolean fromUser) {
        float target = checked ? 1f : 0f;
        if (this.isChecked == checked) {
            if (animator != null && animator.isRunning()) return; // 正在朝目标平滑位移中，不打断动画
            if (progress == target) return;
        }
        this.isChecked = checked;

        if (animator != null) {
            animator.cancel();
            animator = null;
        }

        if (animate && isAttachedToWindow()) {
            animator = ValueAnimator.ofFloat(progress, target);
            animator.setDuration(180);
            // 严格对齐 CSS: cubic-bezier(.2,.8,.4,1)
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

        // 仅在明确来自用户交互时才触发监听回调，杜绝数据绑定/列表复用时的级联误杀
        if (fromUser && listener != null) {
            listener.onCheckedChanged(this, this.isChecked);
        }
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener listener) {
        this.listener = listener;
    }
}
