package com.deepseekharness.app.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/** Intercepts deliberate horizontal swipes while leaving vertical child gestures alone. */
public class SwipeFrameLayout extends FrameLayout {
    public interface OnSwipeListener {
        void onSwipeLeft();
        void onSwipeRight();
    }

    private final int touchSlop;
    private float downX;
    private float downY;
    private boolean trackingSwipe;
    private OnSwipeListener swipeListener;
    private boolean swipeEnabled = true;

    public SwipeFrameLayout(Context context) {
        this(context, null);
    }

    public SwipeFrameLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SwipeFrameLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public void setOnSwipeListener(OnSwipeListener listener) {
        swipeListener = listener;
    }

    public void setSwipeEnabled(boolean enabled) {
        swipeEnabled = enabled;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (!swipeEnabled) return super.onInterceptTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                trackingSwipe = false;
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.25f) {
                    trackingSwipe = true;
                    return true;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                trackingSwipe = false;
                break;
        }
        return super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                return trackingSwipe || super.onTouchEvent(event);
            case MotionEvent.ACTION_UP:
                if (trackingSwipe) {
                    float dx = event.getX() - downX;
                    if (Math.abs(dx) >= getWidth() * 0.18f && swipeListener != null) {
                        if (dx < 0) swipeListener.onSwipeLeft();
                        else swipeListener.onSwipeRight();
                    }
                    trackingSwipe = false;
                    return true;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                trackingSwipe = false;
                return true;
        }
        return super.onTouchEvent(event);
    }
}
