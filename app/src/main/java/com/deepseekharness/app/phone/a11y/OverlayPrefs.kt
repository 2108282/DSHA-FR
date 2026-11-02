package com.deepseekharness.app.phone.a11y

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.roundToInt

/**
 * 设置 → 手机操控 → 悬浮条: 闲置时不透明度 (the ball's, when left alone) and 「闲置时自动收起」
 * (an idle capsule folds into the ball), in the overlay's own preferences file (with the ball's
 * dock). The settings page writes them through `dsh/phone` (`setOverlayPrefs`); a showing
 * overlay picks changes up at once through [SharedPreferences.OnSharedPreferenceChangeListener].
 */
data class OverlayPrefs(val idleOpacity: Float = DEFAULT_OPACITY, val autoFold: Boolean = false) {
    fun toMap(): Map<String, Any?> = linkedMapOf("idleOpacity" to idleOpacity.toDouble(), "autoCollapse" to autoFold)

    companion object {
        const val FILE = "dsh_phone_overlay"
        const val K_OPACITY = "idleOpacity"
        const val K_AUTO_FOLD = "autoFold"
        const val K_RETURN_TO_APP = "returnToApp"

        /**
         * 70 %: an idle ball still reads as there, while the page behind shows through it. Only
         * the ball fades (all of it alike); the capsule and the card stay solid, since they carry
         * the line (docs/design/phone-overlay.md).
         */
        const val DEFAULT_OPACITY = 0.7f
        const val MIN_OPACITY = 0.3f
        const val MAX_OPACITY = 1f

        @JvmStatic
        fun returnToApp(context: Context): Boolean =
            prefs(context).getBoolean(K_RETURN_TO_APP, true)

        @JvmStatic
        fun setReturnToApp(context: Context, value: Boolean) {
            prefs(context).edit().putBoolean(K_RETURN_TO_APP, value).apply()
        }

        /** Within 30–100 %, on whole percents (the slider's steps are 5). */
        fun clampOpacity(v: Float): Float =
            if (v.isNaN()) DEFAULT_OPACITY else ((v.coerceIn(MIN_OPACITY, MAX_OPACITY) * 100).roundToInt() / 100f)

        fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

        fun read(context: Context): OverlayPrefs = from(prefs(context))

        fun from(p: SharedPreferences): OverlayPrefs = OverlayPrefs(
            clampOpacity(p.getFloat(K_OPACITY, DEFAULT_OPACITY)),
            p.getBoolean(K_AUTO_FOLD, false),
        )

        /** Writes what [map] carries (`idleOpacity` 0.3–1, `autoCollapse`); the rest stays. */
        fun write(context: Context, map: Map<*, *>?): OverlayPrefs {
            val p = prefs(context)
            val edit = p.edit()
            (map?.get("idleOpacity") as? Number)?.let { edit.putFloat(K_OPACITY, clampOpacity(it.toFloat())) }
            (map?.get("autoCollapse") as? Boolean)?.let { edit.putBoolean(K_AUTO_FOLD, it) }
            edit.apply()
            return from(p)
        }
    }
}
