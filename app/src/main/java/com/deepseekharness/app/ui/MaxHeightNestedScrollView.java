package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.widget.NestedScrollView;

/** 支持 android:maxHeight 的滚动区域；内容较少时仍按内容高度收紧。 */
public final class MaxHeightNestedScrollView extends NestedScrollView {
    private final int maxHeight;

    public MaxHeightNestedScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        TypedArray values = context.obtainStyledAttributes(attrs, new int[]{android.R.attr.maxHeight});
        maxHeight = values.getDimensionPixelSize(0, Integer.MAX_VALUE);
        values.recycle();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int mode = View.MeasureSpec.getMode(heightMeasureSpec);
        int size = View.MeasureSpec.getSize(heightMeasureSpec);
        if (mode == View.MeasureSpec.UNSPECIFIED || size > maxHeight) {
            heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
