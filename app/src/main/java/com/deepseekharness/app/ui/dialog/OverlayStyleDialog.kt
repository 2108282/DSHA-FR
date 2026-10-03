package com.deepseekharness.app.ui.dialog

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.deepseekharness.app.OverlayController
import com.deepseekharness.app.util.Constants

/**
 * 悬浮条外观与行为配置弹窗：独立子菜单组件，支持底色预设、透明度/行数/字号/停留时间及即时预览。
 */
object OverlayStyleDialog {

    fun show(context: Context) {
        val app = context.applicationContext
        val sp = context.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)

        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, 0)
        }

        fun sectionLabel(text: String): TextView = TextView(context).apply {
            this.text = text
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#4A5568"))
            setPadding(0, dp(10), 0, dp(4))
        }

        box.addView(sectionLabel("底色预设"))
        val pickedBg = intArrayOf(sp.getInt(OverlayController.K_BG, 0))
        val swatches = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val cells = arrayOfNulls<TextView>(OverlayController.BG_PRESETS.size)

        fun paintSwatches() {
            for (i in cells.indices) {
                val cell = cells[i] ?: continue
                val bg = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(-0x1000000 or OverlayController.BG_PRESETS[i])
                    if (i == pickedBg[0]) setStroke(dp(2), Color.parseColor("#7DA7F4"))
                }
                cell.background = bg
            }
        }

        for (i in OverlayController.BG_PRESETS.indices) {
            val idx = i
            val cell = TextView(context).apply {
                text = OverlayController.BG_NAMES[i]
                setTextColor(-0x1)
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(10), dp(6), dp(10))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = dp(4)
                }
                setOnClickListener {
                    pickedBg[0] = idx
                    paintSwatches()
                }
            }
            cells[i] = cell
            swatches.addView(cell)
        }
        paintSwatches()
        box.addView(swatches)

        fun slider(title: String, min: Int, max: Int, value: Int, unit: String): SeekBar {
            val label = sectionLabel("$title：$value$unit")
            box.addView(label)
            val bar = SeekBar(context).apply {
                this.max = max
                progress = value
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(b: SeekBar?, p: Int, fromUser: Boolean) {
                        val fixed = Math.max(min, p)
                        label.text = "$title：$fixed$unit"
                    }
                    override fun onStartTrackingTouch(b: SeekBar?) {}
                    override fun onStopTrackingTouch(b: SeekBar?) {
                        if (progress < min) progress = min
                    }
                })
            }
            box.addView(bar)
            return bar
        }

        val alpha = slider("底色不透明度", 20, 100, sp.getInt(OverlayController.K_ALPHA, OverlayController.DEF_ALPHA), "%")
        val lines = slider("最多显示行数", 1, 8, sp.getInt(OverlayController.K_LINES, OverlayController.DEF_LINES), " 行")
        val wide = slider("字体大小", 6, 20, sp.getInt(OverlayController.K_TEXT_SP, OverlayController.DEF_TEXT_SP), " sp")
        val hold = slider("无新内容停留", 2, 60, sp.getInt(OverlayController.K_HOLD, OverlayController.DEF_HOLD), " 秒")

        val think = CheckBox(context).apply {
            text = "显示思考过程（reasoning，文字更丰富）"
            isChecked = sp.getBoolean(OverlayController.K_REASONING, false)
        }
        box.addView(think)

        val cmd = CheckBox(context).apply {
            text = "工具调用带上命令原文"
            isChecked = sp.getBoolean(OverlayController.K_COMMAND, true)
        }
        box.addView(cmd)

        val confirmHere = CheckBox(context).apply {
            text = "危险命令在悬浮条上直接批准"
            isChecked = sp.getBoolean(OverlayController.K_CONFIRM, true)
        }
        box.addView(confirmHere)

        val scroll = ScrollView(context).apply { addView(box) }

        val saveRunnable = Runnable {
            sp.edit()
                .putInt(OverlayController.K_BG, pickedBg[0])
                .putInt(OverlayController.K_ALPHA, Math.max(20, alpha.progress))
                .putInt(OverlayController.K_LINES, Math.max(1, lines.progress))
                .putInt(OverlayController.K_TEXT_SP, Math.max(6, wide.progress))
                .putInt(OverlayController.K_HOLD, Math.max(2, hold.progress))
                .putBoolean(OverlayController.K_REASONING, think.isChecked)
                .putBoolean(OverlayController.K_COMMAND, cmd.isChecked)
                .putBoolean(OverlayController.K_CONFIRM, confirmHere.isChecked)
                .apply()
        }

        AlertDialog.Builder(context)
            .setTitle("悬浮条外观与行为")
            .setView(scroll)
            .setPositiveButton("保存") { _, _ ->
                saveRunnable.run()
                OverlayController.applyStyleNow(app)
                Toast.makeText(context, "已保存（下一条输出即生效）", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("预览") { _, _ ->
                saveRunnable.run()
                if (!OverlayController.permitted(context)) {
                    Toast.makeText(context, "未开启悬浮窗权限，请先在系统设置中允许", Toast.LENGTH_LONG).show()
                    return@setNeutralButton
                }
                OverlayController.applyStyleNow(app)
                OverlayController.push(app, "preview", "text", "这是预览：AI 的回复会像这样流出来，调工具时会变成「⚙ 正在执行命令: ls -la」这种。")
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
