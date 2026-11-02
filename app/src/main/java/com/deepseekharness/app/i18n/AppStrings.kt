package com.deepseekharness.app.i18n

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

object AppStrings {
    const val APP_NAME = "DSHA"

    fun isEnglish(ctx: Context): Boolean = false

    fun context(ctx: Context): Context = ctx.applicationContext ?: ctx

    fun get(ctx: Context, @StringRes id: Int): String = context(ctx).getString(id)

    fun get(ctx: Context, @StringRes id: Int, vararg args: Any): String = context(ctx).getString(id, *args)

    fun plural(ctx: Context, @PluralsRes id: Int, count: Int, vararg args: Any): String =
        context(ctx).resources.getQuantityString(id, count, *args)
}
