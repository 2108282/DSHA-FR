package com.deepseekharness.app.ui;

import android.content.Context;
import androidx.appcompat.app.AlertDialog;
import com.deepseekharness.app.R;

/**
 * 统一应用内弹窗排版、按钮与窗口动画，基于纯原生 AppCompat 架构，彻底脱钩 MaterialTheme 约束。
 */
public class DshaDialogBuilder extends AlertDialog.Builder {

  public DshaDialogBuilder(Context context) {
    super(context, R.style.Dialog_DSHA_Alert);
  }

  @Override
  public AlertDialog create() {
    AlertDialog dialog = super.create();
    if (dialog.getWindow() != null) {
      android.view.Window window = dialog.getWindow();
      window.setWindowAnimations(R.style.Animation_DSHA_Dialog);
      window
          .getDecorView()
          .post(
              () -> {
                if (dialog.isShowing() && window.getDecorView().getWindowToken() != null)
                  sizeOnce(window, window.getDecorView());
              });
    }
    return dialog;
  }

  private void sizeOnce(android.view.Window window, android.view.View view) {
    android.view.WindowManager.LayoutParams attributes = window.getAttributes();
    int gravity = attributes.gravity;
    if ((gravity & android.view.Gravity.VERTICAL_GRAVITY_MASK) == android.view.Gravity.BOTTOM
        || attributes.width == android.view.ViewGroup.LayoutParams.MATCH_PARENT) return;
    {
      android.content.res.Resources resources = getContext().getResources();
      float density = resources.getDisplayMetrics().density;
      int width =
          Math.min(
              Math.round(560 * density),
              Math.min(
                  resources.getDisplayMetrics().widthPixels,
                  Math.round(resources.getConfiguration().screenWidthDp * density)));
      int maxHeight = Math.round((resources.getConfiguration().screenHeightDp - 24) * density);
      int height = view.getHeight() > maxHeight ? maxHeight : attributes.height;
      if (width != attributes.width || height != attributes.height) window.setLayout(width, height);
    }
  }
}
