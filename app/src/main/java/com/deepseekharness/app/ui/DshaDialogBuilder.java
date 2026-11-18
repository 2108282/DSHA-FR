package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.ColorUtils;

import com.deepseekharness.app.R;

/** Common entry point for application dialogs: window shape, Monet accents and custom card views. */
public class DshaDialogBuilder extends AlertDialog.Builder {

  private final Context context;
  private View customView;
  private boolean isCustomView = false;

  public DshaDialogBuilder(Context context) {
    super(context, R.style.Dialog_DSHA_Alert);
    this.context = context;
  }

  @Override
  public AlertDialog.Builder setView(View view) {
    isCustomView = true;
    if (view != null && !containsModernCard(view) && view.getBackground() == null) {
      customView = wrapInCard(view);
    } else {
      customView = view;
    }
    return super.setView(customView);
  }

  private View wrapInCard(View content) {
    float density = context.getResources().getDisplayMetrics().density;
    int inset = Math.round(16 * density);
    int innerPadding = Math.round(20 * density);
    FrameLayout frame = new FrameLayout(context) {
      @Override
      protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int maxHeight = Math.round(getResources().getDisplayMetrics().heightPixels * .86f);
        if (View.MeasureSpec.getMode(heightMeasureSpec) != View.MeasureSpec.UNSPECIFIED) {
          maxHeight = Math.min(maxHeight, View.MeasureSpec.getSize(heightMeasureSpec));
        }
        super.onMeasure(widthMeasureSpec,
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST));
      }
    };
    frame.setPadding(inset, inset, inset, inset);
    ModernCardView card = new ModernCardView(context);
    card.setOrientation(ModernCardView.VERTICAL);
    card.setPadding(innerPadding, innerPadding, innerPadding, innerPadding);
    card.addView(content, new android.widget.LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    frame.addView(card, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    return frame;
  }

  @Override
  public AlertDialog.Builder setView(int layoutResId) {
    View view = LayoutInflater.from(context).inflate(layoutResId, null, false);
    return setView(view);
  }

  private static boolean containsModernCard(View view) {
    if (view instanceof ModernCardView) return true;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        if (containsModernCard(group.getChildAt(i))) return true;
      }
    }
    return false;
  }

  @Override
  public AlertDialog create() {
    AlertDialog dialog = super.create();
    Window window = dialog.getWindow();
    if (window != null) {
      window.setWindowAnimations(R.style.Animation_DSHA_Dialog);
      if (isCustomView) {
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
      } else {
        window.setBackgroundDrawable(createDialogBackground(context));
      }
    }
    dialog.setOnShowListener(ignored -> applyShownTheme(dialog));
    return dialog;
  }

  private void applyShownTheme(AlertDialog dialog) {
    MonetEngine.PaletteInfo palette = MonetEngine.resolveCurrentPalette(context);
    Window window = dialog.getWindow();
    if (window != null && !isCustomView) {
      window.setBackgroundDrawable(createDialogBackground(context));
    }

    if (isCustomView) {
      View content = dialog.getWindow() == null ? null : dialog.getWindow().getDecorView();
      if (content != null) {
        MonetEngine.applyToViewTree(content, palette);
        tintModernCards(content, palette.primaryColor, palette.isMonetActive);
      }
    }

    int primary = palette.primaryColor;
    Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
    Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
    Button neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
    styleDialogButton(positive, primary, true);
    styleDialogButton(negative, primary, false);
    styleDialogButton(neutral, primary, false);
    TextView title = dialog.findViewById(androidx.appcompat.R.id.alertTitle);
    if (title != null) title.setTextColor(context.getColor(R.color.text));
    TextView message = dialog.findViewById(android.R.id.message);
    if (message != null) message.setTextColor(context.getColor(R.color.text_secondary));
  }

  private static void tintModernCards(View view, int primaryColor, boolean monetActive) {
    if (view instanceof ModernCardView) {
      ((ModernCardView) view).applyDialogPalette(primaryColor, monetActive);
    }
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        tintModernCards(group.getChildAt(i), primaryColor, monetActive);
      }
    }
  }

  private static GradientDrawable createDialogBackground(Context context) {
    MonetEngine.PaletteInfo palette = MonetEngine.resolveCurrentPalette(context);
    int base = context.getColor(R.color.card);
    int fill = palette.isMonetActive ? ColorUtils.blendARGB(base, palette.primaryColor, 0.035f) : base;
    int borderBase = context.getColor(R.color.line);
    int border = palette.isMonetActive ? ColorUtils.blendARGB(borderBase, palette.primaryColor, 0.22f) : borderBase;
    GradientDrawable background = new GradientDrawable();
    background.setShape(GradientDrawable.RECTANGLE);
    background.setColor(fill);
    background.setCornerRadius(dp(context, 24));
    background.setStroke(dp(context, 1), border);
    return background;
  }

  private static void styleDialogButton(Button button, int primary, boolean emphasized) {
    if (button == null) return;
    int backgroundColor = emphasized ? primary : ColorUtils.blendARGB(
        button.getContext().getColor(R.color.card), primary, 0.10f);
    int textColor = !emphasized
        ? primary
        : (ColorUtils.calculateContrast(Color.BLACK, backgroundColor)
                    >= ColorUtils.calculateContrast(Color.WHITE, backgroundColor)
            ? Color.BLACK
            : Color.WHITE);
    GradientDrawable shape = new GradientDrawable();
    shape.setShape(GradientDrawable.RECTANGLE);
    shape.setColor(backgroundColor);
    shape.setCornerRadius(dp(button.getContext(), 16));
    GradientDrawable mask = new GradientDrawable();
    mask.setShape(GradientDrawable.RECTANGLE);
    mask.setColor(Color.WHITE);
    mask.setCornerRadius(dp(button.getContext(), 16));
    button.setBackground(new RippleDrawable(
        ColorStateList.valueOf(ColorUtils.setAlphaComponent(primary, 0x35)), shape, mask));
    button.setTextColor(textColor);
    button.setAllCaps(false);
  }

  private static int dp(Context context, int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }
}
