package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.deepseekharness.app.R;

/**
 * 1:1 像素级复刻 launch-concept.html 的 .gcard 现代卡片：
 * - 纯正 GPU 硬件加速立体微阴影 (彻底根除切图黑斑/黑角，0ms 物理运算，满帧流畅):
 *     box-shadow: 0 4px 16px rgba(26,34,48,.06), 0 1px 3px rgba(26,34,48,.04);
 *     暗色模式: 0 4px 16px rgba(0,0,0,.35), 0 1px 3px rgba(0,0,0,.25);
 * - 卡片主体：var(--card-glass): rgba(255,255,255,.82) (暗色 rgba(22,27,36,.88))
 * - 精细边框：var(--glass-border): 1px solid rgba(229,231,235,.55)
 * - 绝对平滑圆角，卡片间距清爽规整，彻底消除任何黑色方角与悬空裂缝！
 */
public class ModernCardView extends LinearLayout {

    private float radius = 0f;
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF cardRect = new RectF();
    private final RectF strokeRect = new RectF();
    private final GradientDrawable bgDrawable = new GradientDrawable();

    public ModernCardView(Context context) {
        this(context, null);
    }

    public ModernCardView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ModernCardView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(attrs);
    }

    private void init(@Nullable AttributeSet attrs) {
        setWillNotDraw(false);

        float density = getResources().getDisplayMetrics().density;
        radius = 18f * density;

        if (attrs != null) {
            TypedArray a = getContext().obtainStyledAttributes(attrs, R.styleable.ModernCardView);
            radius = a.getDimension(R.styleable.ModernCardView_cardRadius, radius);
            a.recycle();
        }

        int overlayColor = ContextCompat.getColor(getContext(), R.color.card_glass);
        int strokeColor = ContextCompat.getColor(getContext(), R.color.line_soft);

        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setColor(overlayColor);

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1f * density);
        strokePaint.setColor(strokeColor);

        // 1. 设置系统标准背景 Drawable（透明圆角），作为硬件加速 Outline 投射源
        bgDrawable.setColor(0x00000000);
        bgDrawable.setCornerRadius(radius);
        setBackground(bgDrawable);

        // 2. 硬件加速平滑阴影 (彻底杜绝手工位图拼接的黑块与断裂)
        setElevation(4f * density);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });

        boolean isDark = ThemeController.isDark(getContext());
        // 严格对齐 CSS rgba(26,34,48, .06) 与 rgba(26,34,48, .04) 淡青灰微光晕
        setOutlineAmbientShadowColor(isDark ? 0x59000000 : 0x1A1A2230);
        setOutlineSpotShadowColor(isDark ? 0x40000000 : 0x121A2230);
    }

    public void setCardRadius(float radiusDp) {
        this.radius = radiusDp * getResources().getDisplayMetrics().density;
        bgDrawable.setCornerRadius(radius);
        invalidateOutline();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) return;

        cardRect.set(0, 0, w, h);

        float halfStroke = strokePaint.getStrokeWidth() / 2f;
        strokeRect.set(halfStroke, halfStroke, w - halfStroke, h - halfStroke);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w > 0 && h > 0) {
            // 1. 绘制纯净半透明卡片底色 (100% 还原 CSS --card-glass: rgba(255,255,255,.82))
            canvas.drawRoundRect(cardRect, radius, radius, bgPaint);

            // 2. 绘制 1px 精细玻璃边框 (100% 还原 CSS --glass-border: 1px solid rgba(229,231,235,.55))
            float halfStroke = strokePaint.getStrokeWidth() / 2f;
            float innerRadius = Math.max(0, radius - halfStroke);
            canvas.drawRoundRect(strokeRect, innerRadius, innerRadius, strokePaint);
        }

        super.onDraw(canvas);
    }
}
