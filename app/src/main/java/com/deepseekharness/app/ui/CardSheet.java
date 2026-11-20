package com.deepseekharness.app.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import com.deepseekharness.app.R;

/** 内容短时收紧，长时滚动正文并保留底部操作。 */
final class CardSheet {
  static androidx.appcompat.app.AlertDialog create(Context context, CardPage page) {
    MonetEngine.PaletteInfo palette = MonetEngine.resolveCurrentPalette(context);
    page.root.applyDialogPalette(palette.primaryColor, palette.isMonetActive);
    MonetEngine.applyToViewTree(page.root, palette);
    page.scroll.setFillViewport(false);
    page.scroll.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
    FrameLayout container =
        new FrameLayout(context) {
          @Override
          protected void onMeasure(int widthSpec, int heightSpec) {
            int limit = Math.round(getResources().getDisplayMetrics().heightPixels * .86f);
            if (View.MeasureSpec.getMode(heightSpec) != View.MeasureSpec.UNSPECIFIED)
              limit = Math.min(limit, View.MeasureSpec.getSize(heightSpec));
            super.onMeasure(
                widthSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST));
          }
        };
    container.addView(page.root, new FrameLayout.LayoutParams(-1, -2));
    return new DshaDialogBuilder(context).setView(container).create();
  }

  private static int dp(Context context, int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }

  static void show(androidx.appcompat.app.AlertDialog dialog, Context context) {
    View decorContent = dialog.getWindow() == null ? null : dialog.getWindow().getDecorView();
    if (decorContent != null) {
      MonetEngine.PaletteInfo palette = MonetEngine.resolveCurrentPalette(context);
      MonetEngine.applyToViewTree(decorContent, palette);
    }
    dialog.show();
    if (dialog.getWindow() != null) {
      dialog.getWindow().setGravity(Gravity.BOTTOM);
      dialog.getWindow().setLayout(-1, -2);
      dialog
          .getWindow()
          .setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
  }

  static void choices(
      Context context,
      String title,
      java.util.function.Supplier<java.util.List<String>> source,
      java.util.function.IntConsumer selected) {
    choices(context, title, source, -1, selected);
  }

  static void choices(
      Context context,
      String title,
      java.util.function.Supplier<java.util.List<String>> source,
      int selectedPosition,
      java.util.function.IntConsumer selected) {
    choices(context, title, source, selectedPosition, position -> true, selected);
  }

  static void choices(
      Context context,
      String title,
      java.util.function.Supplier<java.util.List<String>> source,
      int selectedPosition,
      java.util.function.IntPredicate enabled,
      java.util.function.IntConsumer selected) {
    CardPage page = new CardPage(context, title, "");
    var dialog = create(context, page);
    java.util.List<String> values = source.get();
    for (int i = 0; i < values.size(); i++) {
      final int position = i;
      boolean isSelected = position == selectedPosition;
      LinearLayout row = new LinearLayout(context);
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setMinimumHeight(page.dp(52));
      row.setPadding(page.dp(16), page.dp(8), page.dp(16), page.dp(8));
      MonetEngine.PaletteInfo palette = MonetEngine.resolveCurrentPalette(context);
      boolean itemEnabled = enabled.test(position);
      row.setBackground(choiceBackground(context, itemEnabled, palette.primaryColor));
      row.setFocusable(itemEnabled);
      row.setClickable(itemEnabled);
      row.setEnabled(itemEnabled);
      row.setSelected(isSelected);
      androidx.core.view.ViewCompat.setStateDescription(
          row, isSelected ? com.deepseekharness.app.util.UiText.choose("已选择", "Selected") : null);
      TextView label = page.text(values.get(i), 15, R.color.text);
      if (!itemEnabled) label.setAlpha(0.45f);
      if (isSelected) label.setTextColor(palette.primaryColor);
      row.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
      if (isSelected) {
        TextView check = page.text("✓", 18, R.color.text);
        check.setTextColor(palette.primaryColor);
        check.setContentDescription(com.deepseekharness.app.util.UiText.choose("已选择", "Selected"));
        row.addView(check);
      }
      LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
      rowParams.bottomMargin = page.dp(6);
      page.content.addView(row, rowParams);
      row.setOnClickListener(
          v -> {
            dialog.dismiss();
            selected.accept(position);
          });
    }
    page.button(
        page.footer,
        com.deepseekharness.app.util.UiText.choose("取消", "Cancel"),
        false,
        dialog::dismiss);
    show(dialog, context);
  }

  private static android.graphics.drawable.Drawable choiceBackground(
      Context context, boolean enabled, int primary) {
    if (!enabled) {
      android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
      shape.setColor(context.getColor(R.color.raised));
      shape.setCornerRadius(dp(context, 14));
      return shape;
    }
    return DshaPopupTheme.itemBackground(context, primary, 14);
  }

  static void show(Context context, String title, String body) {
    CardPage page = new CardPage(context, title, "");
    TextView text = page.text(body, 13, R.color.text_secondary);
    text.setPadding(0, page.dp(14), 0, 0);
    text.setTextIsSelectable(true);
    text.setLineSpacing(page.dp(5), 1);
    page.content.addView(text);
    var dialog = create(context, page);
    page.button(
        page.footer,
        com.deepseekharness.app.util.UiText.choose("关闭", "Close"),
        false,
        dialog::dismiss);
    show(dialog, context);
  }
}
