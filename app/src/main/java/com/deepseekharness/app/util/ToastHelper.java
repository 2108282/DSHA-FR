package com.deepseekharness.app.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.deepseekharness.app.core.ConfigStore;

/**
 * 全局统一 Toast 发送与拦截门面：
 * 1. 零 I/O 内存守卫：利用 volatile 镜像变量做纳秒级拦截判断，关闭时直接截断，杜绝系统 Binder IPC 与 GPU 动画开销；
 * 2. 线程安全自适应：无论在主线程还是后台线程调用，自动路由到主线程安全派发；
 * 3. 防御式容错：包裹 try-catch，彻底消除 BadTokenException 等系统级异常崩溃；
 * 4. 兼容性支持：既支持极简的 ToastHelper.show(...)，也支持 makeText(...).show() 链式调用。
 */
public final class ToastHelper {

    private static volatile Boolean sEnabled = null;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private ToastHelper() {
    }

    /**
     * 同步内存缓存状态（由 ConfigStore 或设置项在变更时调用，零延迟生效）
     */
    public static void syncEnabled(boolean enabled) {
        sEnabled = enabled;
    }

    /**
     * 判断当前 Toast 是否处于启用状态（热路径仅读取 volatile 变量，耗时 < 1ns）
     */
    public static boolean isEnabled(Context context) {
        Boolean cached = sEnabled;
        if (cached != null) {
            return cached;
        }
        if (context != null) {
            boolean val = ConfigStore.get(context).isToastNotificationEnabled();
            sEnabled = val;
            return val;
        }
        return true;
    }

    /**
     * 统一显示短 Toast
     */
    public static void show(Context context, CharSequence message) {
        show(context, message, Toast.LENGTH_SHORT);
    }

    /**
     * 统一根据资源 ID 显示短 Toast
     */
    public static void show(Context context, int resId) {
        if (context == null) return;
        try {
            show(context, context.getString(resId), Toast.LENGTH_SHORT);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 统一显示指定时长的 Toast
     */
    public static void show(Context context, CharSequence message, int duration) {
        if (context == null || message == null) return;
        if (!isEnabled(context)) {
            // 开关关闭，纳秒级就地截断，彻底避免 IPC 传输与系统服务负担
            return;
        }
        final Context appCtx = context.getApplicationContext();
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                Toast.makeText(appCtx, message, duration).show();
            } catch (Throwable ignored) {
            }
        } else {
            MAIN_HANDLER.post(() -> {
                try {
                    Toast.makeText(appCtx, message, duration).show();
                } catch (Throwable ignored) {
                }
            });
        }
    }

    /**
     * 兼容传统 Toast.makeText 语法的流式句柄
     */
    public static class Builder {
        private final Context context;
        private final CharSequence message;
        private final int duration;

        private Builder(Context context, CharSequence message, int duration) {
            this.context = context;
            this.message = message;
            this.duration = duration;
        }

        public void show() {
            ToastHelper.show(context, message, duration);
        }
    }

    public static Builder makeText(Context context, CharSequence message, int duration) {
        return new Builder(context, message, duration);
    }

    public static Builder makeText(Context context, int resId, int duration) {
        CharSequence text = "";
        if (context != null) {
            try {
                text = context.getString(resId);
            } catch (Throwable ignored) {
            }
        }
        return new Builder(context, text, duration);
    }
}
