package com.deepseekharness.app.ui;

import android.content.Context;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatSpinner;

import java.util.ArrayList;
import java.util.List;

import com.deepseekharness.app.util.UiText;

/** 保留 Spinner 适配器与选择回调，只把系统下拉菜单换为统一的 Skia/莫奈卡片选择面板。 */
public final class DshaSelectView extends AppCompatSpinner {
  public DshaSelectView(Context context) {
    super(context);
    skin();
  }

  public DshaSelectView(Context context, AttributeSet attrs) {
    super(context, attrs);
    skin();
  }

  public DshaSelectView(Context context, AttributeSet attrs, int style) {
    super(context, attrs, style);
    skin();
  }

  private void skin() {
    setBackgroundResource(com.deepseekharness.app.R.drawable.bg_input);
    setPadding(dp(8), 0, dp(8), 0);
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  /** Spinner 的父实现会打开系统菜单；这里转到公共卡片选择器，避免二级弹窗样式分叉。 */
  @android.annotation.SuppressLint("MissingSuperCall")
  @Override
  public boolean performClick() {
    if (!isEnabled() || getAdapter() == null) return false;
    sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED);

    List<String> values = new ArrayList<>();
    int count = getAdapter().getCount();
    for (int i = 0; i < count; i++) {
      Object item = getAdapter().getItem(i);
      values.add(item == null ? "" : String.valueOf(item));
    }
    String title = getPrompt() == null
        ? UiText.choose("请选择", "Choose an option")
        : String.valueOf(getPrompt());
    int selected = getSelectedItemPosition();
    CardSheet.choices(
        getContext(),
        title,
        () -> values,
        selected,
        position -> !(getAdapter() instanceof android.widget.ListAdapter)
            || ((android.widget.ListAdapter) getAdapter()).isEnabled(position),
        position -> {
          setSelection(position);
          sendAccessibilityEvent(
              android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SELECTED);
        });
    return true;
  }
}
