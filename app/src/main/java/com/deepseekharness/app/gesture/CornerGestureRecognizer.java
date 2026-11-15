package com.deepseekharness.app.gesture;

/**
 * Single-pointer bottom-corner gesture state machine with no Android dependencies.
 * All event coordinates and screen dimensions are pixels in the same coordinate space.
 * The caller must cancel on additional pointers, ACTION_CANCEL, or loss of ownership.
 */
public final class CornerGestureRecognizer {
    private static final float CORNER_WIDTH_DP = 48f;
    private static final float DEFAULT_BOTTOM_HEIGHT_DP = 25f;
    private static final float TRIGGER_DISTANCE_DP = 55f;

    private final float cornerWidthPx;
    private final float bottomHeightPx;
    private final float triggerDistancePx;

    private float startX;
    private float startY;
    private float screenWidth;
    private float screenHeight;
    private boolean tracking;
    private boolean triggered;
    private boolean left;

    public CornerGestureRecognizer(float density) {
        this(density, DEFAULT_BOTTOM_HEIGHT_DP);
    }

    /** Allows callers to retain a different bottom hot-zone height, e.g. 20.5dp. */
    public CornerGestureRecognizer(float density, float bottomHeightDp) {
        if (!isPositiveFinite(density) || !isPositiveFinite(bottomHeightDp)) {
            throw new IllegalArgumentException("Density and bottom height must be finite and positive");
        }
        cornerWidthPx = CORNER_WIDTH_DP * density;
        bottomHeightPx = bottomHeightDp * density;
        triggerDistancePx = TRIGGER_DISTANCE_DP * density;
        if (!isPositiveFinite(cornerWidthPx) || !isPositiveFinite(bottomHeightPx)
                || !isPositiveFinite(triggerDistancePx)) {
            throw new IllegalArgumentException("Pixel dimensions must be finite and positive");
        }
    }

    /**
     * Replaces any previous gesture and starts tracking only inside a bottom corner.
     * Screen coordinates use [0, width) and [0, height); inner hot-zone edges are inclusive.
     * When corner zones overlap on a narrow screen, the nearest edge selects the side
     * (the exact midpoint selects left). Invalid dimensions or coordinates return false.
     */
    public boolean begin(float x, float y, float width, float height) {
        reset();
        if (!isPositiveFinite(width) || !isPositiveFinite(height)
                || !isOnScreen(x, y, width, height) || y < height - bottomHeightPx) {
            return false;
        }
        boolean inLeft = x <= cornerWidthPx;
        boolean inRight = x >= width - cornerWidthPx;
        if (!inLeft && !inRight) {
            return false;
        }
        startX = x;
        startY = y;
        screenWidth = width;
        screenHeight = height;
        left = inLeft && (!inRight || x <= width / 2f);
        tracking = true;
        return true;
    }

    /**
     * Returns displacement from DOWN divided by 55dp, clamped to [0, 1]. Before the
     * threshold this may decrease when moving back. At or above the threshold it
     * immediately latches isTriggered() and returns 1 for subsequent valid moves.
     * There are no direction or speed restrictions. Invalid coordinates cancel the
     * gesture; moves without an active gesture return 0 and cannot start one.
     * Compare isTriggered() before and after this call to dispatch exactly once.
     */
    public float move(float x, float y) {
        if (!tracking) {
            return 0f;
        }
        if (!isOnScreen(x, y, screenWidth, screenHeight)) {
            cancel();
            return 0f;
        }
        if (triggered) {
            return 1f;
        }
        double distance = Math.hypot((double) x - startX, (double) y - startY);
        if (distance >= triggerDistancePx) {
            triggered = true;
            return 1f;
        }
        return (float) (distance / triggerDistancePx);
    }

    /** Remains true after triggering until end, cancel, reset, or the next begin. */
    public boolean isTriggered() {
        return triggered;
    }

    /** Remains true after triggering so the caller can retain ownership until UP. */
    public boolean isTracking() {
        return tracking;
    }

    /** The selected corner; false when idle or tracking the right corner. */
    public boolean isLeft() {
        return left;
    }

    /** Ends the gesture without performing a final threshold check. */
    public void end() {
        reset();
    }

    /** Discards the entire gesture, including any latched trigger. */
    public void cancel() {
        reset();
    }

    public void reset() {
        tracking = false;
        triggered = false;
        left = false;
        startX = 0f;
        startY = 0f;
        screenWidth = 0f;
        screenHeight = 0f;
    }

    private static boolean isPositiveFinite(float value) {
        return Float.isFinite(value) && value > 0f;
    }

    private static boolean isOnScreen(float x, float y, float width, float height) {
        return Float.isFinite(x) && Float.isFinite(y)
                && x >= 0f && x < width && y >= 0f && y < height;
    }
}
