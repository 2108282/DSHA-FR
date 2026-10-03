package com.deepseekharness.app.ui.dialog;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.deepseekharness.app.OverlayController;
import com.deepseekharness.app.util.Constants;

public final class OverlayStyleDialog {

    private OverlayStyleDialog() {}

    public static void show(Context context) {
        final Context app = context.getApplicationContext();
        final SharedPreferences sp = context.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);

        final float density = context.getResources().getDisplayMetrics().density;
        final java.util.function.IntFunction<Integer> dp = v -> Math.round(v * density);

        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp.apply(16);
        box.setPadding(pad, pad, pad, 0);

        java.util.function.Function<String, TextView> sectionLabel = text -> {
            TextView t = new TextView(context);
            t.setText(text);
            t.setTextSize(13f);
            t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
            t.setTextColor(Color.parseColor("#4A5568"));
            t.setPadding(0, dp.apply(10), 0, dp.apply(4));
            return t;
        };

        box.addView(sectionLabel.apply("底色预设"));
        final int[] pickedBg = {sp.getInt(OverlayController.K_BG, 0)};
        LinearLayout swatches = new LinearLayout(context);
        swatches.setOrientation(LinearLayout.HORIZONTAL);
        final TextView[] cells = new TextView[OverlayController.BG_PRESETS.length];

        Runnable paintSwatches = () -> {
            for (int i = 0; i < cells.length; i++) {
                if (cells[i] == null) continue;
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp.apply(12));
                bg.setColor(0xFF000000 | OverlayController.BG_PRESETS[i]);
                if (i == pickedBg[0]) bg.setStroke(dp.apply(2), Color.parseColor("#7DA7F4"));
                cells[i].setBackground(bg);
            }
        };

        for (int i = 0; i < OverlayController.BG_PRESETS.length; i++) {
            final int idx = i;
            TextView cell = new TextView(context);
            cell.setText(OverlayController.BG_NAMES[i]);
            cell.setTextColor(0xFFFFFFFF);
            cell.setTextSize(11f);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp.apply(6), dp.apply(10), dp.apply(6), dp.apply(10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = dp.apply(4);
            cell.setLayoutParams(lp);
            cell.setOnClickListener(v -> {
                pickedBg[0] = idx;
                paintSwatches.run();
            });
            cells[i] = cell;
            swatches.addView(cell);
        }
        paintSwatches.run();
        box.addView(swatches);

        class SliderHelper {
            SeekBar add(String title, int min, int max, int value, String unit) {
                TextView label = sectionLabel.apply(title + "：" + value + unit);
                box.addView(label);
                SeekBar bar = new SeekBar(context);
                bar.setMax(max);
                bar.setProgress(value);
                bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar b, int p, boolean fromUser) {
                        int fixed = Math.max(min, p);
                        label.setText(title + "：" + fixed + unit);
                    }
                    @Override public void onStartTrackingTouch(SeekBar b) {}
                    @Override public void onStopTrackingTouch(SeekBar b) {
                        if (bar.getProgress() < min) bar.setProgress(min);
                    }
                });
                box.addView(bar);
                return bar;
            }
        }
        SliderHelper sliderHelper = new SliderHelper();

        final SeekBar alpha = sliderHelper.add("底色不透明度", 20, 100, sp.getInt(OverlayController.K_ALPHA, OverlayController.DEF_ALPHA), "%");
        final SeekBar lines = sliderHelper.add("最多显示行数", 1, 8, sp.getInt(OverlayController.K_LINES, OverlayController.DEF_LINES), " 行");
        final SeekBar wide = sliderHelper.add("字体大小", 6, 20, sp.getInt(OverlayController.K_TEXT_SP, OverlayController.DEF_TEXT_SP), " sp");
        final SeekBar hold = sliderHelper.add("无新内容停留", 2, 60, sp.getInt(OverlayController.K_HOLD, OverlayController.DEF_HOLD), " 秒");

        final CheckBox think = new CheckBox(context);
        think.setText("显示思考过程（reasoning，文字更丰富）");
        think.setChecked(sp.getBoolean(OverlayController.K_REASONING, false));
        box.addView(think);

        final CheckBox cmd = new CheckBox(context);
        cmd.setText("工具调用带上命令原文");
        cmd.setChecked(sp.getBoolean(OverlayController.K_COMMAND, true));
        box.addView(cmd);

        final CheckBox confirmHere = new CheckBox(context);
        confirmHere.setText("危险命令在悬浮条上直接批准");
        confirmHere.setChecked(sp.getBoolean(OverlayController.K_CONFIRM, true));
        box.addView(confirmHere);

        ScrollView scroll = new ScrollView(context);
        scroll.addView(box);

        final Runnable saveRunnable = () -> sp.edit()
                .putInt(OverlayController.K_BG, pickedBg[0])
                .putInt(OverlayController.K_ALPHA, Math.max(20, alpha.getProgress()))
                .putInt(OverlayController.K_LINES, Math.max(1, lines.getProgress()))
                .putInt(OverlayController.K_TEXT_SP, Math.max(6, wide.getProgress()))
                .putInt(OverlayController.K_HOLD, Math.max(2, hold.getProgress()))
                .putBoolean(OverlayController.K_REASONING, think.isChecked())
                .putBoolean(OverlayController.K_COMMAND, cmd.isChecked())
                .putBoolean(OverlayController.K_CONFIRM, confirmHere.isChecked())
                .apply();

        new AlertDialog.Builder(context)
                .setTitle("悬浮条外观与行为")
                .setView(scroll)
                .setPositiveButton("保存", (d, w) -> {
                    saveRunnable.run();
                    OverlayController.applyStyleNow(app);
                    Toast.makeText(context, "已保存（下一条输出即生效）", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("预览", (d, w) -> {
                    saveRunnable.run();
                    if (!OverlayController.permitted(context)) {
                        Toast.makeText(context, "未开启悬浮窗权限，请先在系统设置中允许", Toast.LENGTH_LONG).show();
                        return;
                    }
                    OverlayController.applyStyleNow(app);
                    OverlayController.push(app, "preview", "text", "这是预览：AI 的回复会像这样流出来，调工具时会变成「⚙ 正在执行命令: ls -la」这种。");
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
