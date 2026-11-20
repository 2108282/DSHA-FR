package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import com.deepseekharness.app.R;

/** Shared surface, outline, and touch-state styling for anchor-attached popup menus. */
public final class DshaPopupTheme {
  private DshaPopupTheme() {}

  public static MonetEngine.PaletteInfo apply(Context context, View surface) {
    MonetEngine.PaletteInfo palette = MonetEngine.resolveCurrentPalette(context);
    int base = context.getColor(R.color.card);
    int fill = palette.isMonetActive
        ? ColorUtils.blendARGB(base, palette.primaryColor, 0.035f)
        : base;
    int baseOutline = context.getColor(R.color.line_soft);
    int outline = palette.isMonetActive
        ? ColorUtils.blendARGB(baseOutline, palette.primaryColor, 0.18f)
        : baseOutline;
    GradientDrawable background = new GradientDrawable();
    background.setShape(GradientDrawable.RECTANGLE);
    background.setColor(fill);
    background.setCornerRadius(dp(context, 16));
    background.setStroke(dp(context, 1), outline);
    surface.setBackground(background);
    return palette;
  }

  public static Drawable itemBackground(
      Context context,
      int primaryColor,
      int cornerRadiusDp) {
    GradientDrawable mask = new GradientDrawable();
    mask.setShape(GradientDrawable.RECTANGLE);
    mask.setColor(android.graphics.Color.WHITE);
    mask.setCornerRadius(dp(context, cornerRadiusDp));
    // Selection is conveyed by the label and checkmark; fill appears only while interacting.
    return new RippleDrawable(
        ColorStateList.valueOf(ColorUtils.setAlphaComponent(primaryColor, 0x35)), null, mask);
  }

  private static int dp(Context context, int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }
}
