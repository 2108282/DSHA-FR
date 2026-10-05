package com.deepseekharness.app.util;

import java.util.Locale;

/**
 * 界面文案辅助类：优先返回中文，并提供字符串格式化。
 */
public final class UiText {
    private UiText() {}

    public static String choose(String zh, String en) {
        String lang = Locale.getDefault().getLanguage();
        if ("en".equalsIgnoreCase(lang)) {
            return en != null && !en.isEmpty() ? en : zh;
        }
        return zh != null ? zh : "";
    }

    public static String text(String value) {
        return value != null ? value : "";
    }

    public static String format(String template, Object... args) {
        return String.format(Locale.ROOT, template, args);
    }
}
