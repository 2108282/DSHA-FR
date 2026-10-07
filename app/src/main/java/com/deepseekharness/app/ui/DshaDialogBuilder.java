package com.deepseekharness.app.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Window;
import android.view.WindowManager;
import androidx.appcompat.app.AlertDialog;
import com.deepseekharness.app.R;

/**
 * 统一应用内弹窗排版、按钮与窗口动画，基于纯原生 AppCompat 架构，彻底脱钩 MaterialTheme 约束。
 */
public class DshaDialogBuilder extends AlertDialog.Builder {

  private boolean isCustomView = false;

  public DshaDialogBuilder(Context context) {
    super(context, R.style.Dialog_DSHA_Alert);
  }

  @Override
  public AlertDialog.Builder setView(android.view.View view) {
    isCustomView = true;
    return super.setView(view);
  }

  @Override
  public AlertDialog.Builder setView(int layoutResId) {
    isCustomView = true;
    return super.setView(layoutResId);
  }

  @Override
  public AlertDialog create() {
    AlertDialog dialog = super.create();
    if (dialog.getWindow() != null) {
      Window window = dialog.getWindow();
      window.setWindowAnimations(R.style.Animation_DSHA_Dialog);
      if (isCustomView) {
        // 自定义卡片弹窗：将外层 Window 背景设为完全透明，彻底消除双层嵌套产生的黑色扁框
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        // 一步预设窗口属性为 MATCH_PARENT，由内部布局的 padding 自适应居中，严禁在 post 中二次移动
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT);
      }
    }
    return dialog;
  }
}
