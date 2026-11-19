package com.deepseekharness.app.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import java.util.List;
import java.util.function.IntConsumer;

import com.deepseekharness.app.R;

/** Shared Skia-card surfaces for common confirmation and information dialogs. */
public final class DshaCardDialogs {
  private DshaCardDialogs() {}

  public static void choices(
      Context context,
      String title,
      List<String> items,
      int selectedPosition,
      IntConsumer onSelected) {
    CardSheet.choices(context, title, () -> items, selectedPosition, onSelected);
  }

  public static void info(Context context, String title, String message, String buttonText) {
    View content = LayoutInflater.from(context).inflate(R.layout.dialog_model_notice, null, false);
    ((TextView) content.findViewById(R.id.tv_dialog_model_notice_title)).setText(title);
    ((TextView) content.findViewById(R.id.tv_dialog_model_notice_message)).setText(message);
    Button button = content.findViewById(R.id.btnDialogConfirm);
    button.setText(buttonText);
    AlertDialog dialog = new DshaDialogBuilder(context).setView(content).create();
    button.setOnClickListener(v -> dialog.dismiss());
    dialog.show();
  }

  public static AlertDialog confirm(
      Context context,
      String title,
      String message,
      String cancelText,
      String confirmText,
      int iconRes,
      Runnable onConfirm) {
    return confirm(context, title, message, cancelText, confirmText, iconRes, null, onConfirm);
  }

  public static AlertDialog confirm(
      Context context,
      String title,
      String message,
      String cancelText,
      String confirmText,
      int iconRes,
      Runnable onCancel,
      Runnable onConfirm) {
    View content = LayoutInflater.from(context).inflate(R.layout.dialog_confirm_action, null, false);
    ((TextView) content.findViewById(R.id.dialogActionTitle)).setText(title);
    ((TextView) content.findViewById(R.id.dialogActionMessage)).setText(message);
    ((ImageView) content.findViewById(R.id.dialogActionIcon)).setImageResource(iconRes);

    AlertDialog dialog = new DshaDialogBuilder(context)
        .setView(content)
        .setCancelable(true)
        .create();
    Button cancel = content.findViewById(R.id.btnActionCancel);
    Button confirm = content.findViewById(R.id.btnActionConfirm);
    cancel.setText(cancelText);
    confirm.setText(confirmText);
    cancel.setOnClickListener(v -> {
      dialog.dismiss();
      if (onCancel != null) onCancel.run();
    });
    confirm.setOnClickListener(v -> {
      dialog.dismiss();
      if (onConfirm != null) onConfirm.run();
    });
    return dialog;
  }
}
