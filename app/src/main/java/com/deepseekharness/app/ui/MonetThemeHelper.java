package com.deepseekharness.app.ui;

import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.deepseekharness.app.HttpShellService;

import java.io.File;
import java.util.Locale;

/**
 * 快捷抽屉莫奈（Material You）调色板核心引擎：
 * 1. 严格 100% 采用 c8ea259 原版壁纸取色引擎；
 * 2. 严格限制：打开反色（深色模式）之后，坚决不使用莫奈取色，保持纯正经典深色反色；
 * 3. 浅色模式调色板的明度与饱和度严格 100% 对齐 c8ea259 原版。
 */
public final class MonetThemeHelper {

    private MonetThemeHelper() {}

    private static volatile Integer sCachedSeedColor = null;

    /** 清除壁纸颜色缓存（在设置页切换或手动刷新时调用） */
    public static void clearCache(Context context) {
        sCachedSeedColor = null;
        if (context != null) {
            try {
                File cacheFile = new File(context.getCacheDir(), "wallpaper_monet_seed.jpg");
                if (cacheFile.exists()) {
                    cacheFile.delete();
                }
                File infoFile = new File(context.getCacheDir(), "wallpaper_info.xml");
                if (infoFile.exists()) {
                    infoFile.delete();
                }
            } catch (Throwable ignored) {}
        }
    }

    public static void clearCache() {
        sCachedSeedColor = null;
    }

    /**
     * 抽屉完整配色包（包含 Android 原生 View 与 WebView 注入所需的所有颜色）
     */
    public static class Palette {
        public final int cardBgColor;
        public final int textColor;
        public final int textSecondaryColor;
        public final int lineColor;
        public final int handleColor;
        public final int borderColor;

        // WebView 样式所需字符串
        public final String inputBg;
        public final String inputBorder;
        public final String drawerBg;
        public final String menuBg;
        public final String dialogBg;
        public final String selectorBg;
        public final String menuBorder;
        public final String textPrimaryHex;
        public final String textSecondaryHex;
        public final String brandTextHex;
        public final String solidBgHex;

        public Palette(int cardBgColor, int textColor, int textSecondaryColor,
                       int lineColor, int handleColor, int borderColor,
                       String inputBg, String inputBorder, String drawerBg,
                       String menuBg, String dialogBg, String selectorBg,
                       String menuBorder, String textPrimaryHex,
                       String textSecondaryHex, String brandTextHex,
                       String solidBgHex) {
            this.cardBgColor = cardBgColor;
            this.textColor = textColor;
            this.textSecondaryColor = textSecondaryColor;
            this.lineColor = lineColor;
            this.handleColor = handleColor;
            this.borderColor = borderColor;
            this.inputBg = inputBg;
            this.inputBorder = inputBorder;
            this.drawerBg = drawerBg;
            this.menuBg = menuBg;
            this.dialogBg = dialogBg;
            this.selectorBg = selectorBg;
            this.menuBorder = menuBorder;
            this.textPrimaryHex = textPrimaryHex;
            this.textSecondaryHex = textSecondaryHex;
            this.brandTextHex = brandTextHex;
            this.solidBgHex = solidBgHex;
        }
    }

    public static String toHexString(int color) {
        return String.format(Locale.US, "#%06X", (0xFFFFFF & color));
    }

    public static String toRgbaString(int color, float alpha) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        return String.format(Locale.US, "rgba(%d, %d, %d, %.2f)", r, g, b, alpha);
    }

    /**
     * 从当前系统壁纸中提取最具辨识度的鲜艳种子色（Seed Color）
     */
    public static int getWallpaperSeedColor(Context context) {
        if (sCachedSeedColor != null) {
            return sCachedSeedColor;
        }
        if (context == null) {
            return Color.parseColor("#10B981");
        }

        int extracted = 0;

        // 阶段 1：通过 Root 特权通道直取系统壁纸图片（穿透小米澎湃 OS/MIUI/OPPO/vivo 等系统壁纸签名墙）
        try {
            File cacheFile = new File(context.getCacheDir(), "wallpaper_monet_seed.jpg");
            if (!cacheFile.exists() || cacheFile.length() <= 0) {
                String copyCmd = "cp /data/system/users/0/wallpaper " + cacheFile.getAbsolutePath()
                        + " 2>/dev/null || cp /data/system/users/0/wallpaper_orig " + cacheFile.getAbsolutePath()
                        + " 2>/dev/null || cp /data/system/users/0/blurwallpaper " + cacheFile.getAbsolutePath()
                        + " 2>/dev/null; chmod 666 " + cacheFile.getAbsolutePath() + " 2>/dev/null";
                HttpShellService.execRootCommand(copyCmd);
            }

            if (cacheFile.exists() && cacheFile.length() > 0) {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 16;
                Bitmap bmp = BitmapFactory.decodeFile(cacheFile.getAbsolutePath(), opts);
                if (bmp != null) {
                    extracted = extractVibrantFromBitmap(bmp);
                    bmp.recycle();
                }
            }
        } catch (Throwable ignored) {}

        // 阶段 2：优先尝试系统级 WallpaperColors（适用于原生 Pixel/AOSP 系统，API 27+）
        if (extracted == 0) {
            try {
                WallpaperManager wm = WallpaperManager.getInstance(context);
                if (wm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    WallpaperColors wc = wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
                    if (wc != null) {
                        Color p = wc.getPrimaryColor();
                        if (p != null && getSaturation(p.toArgb()) >= 0.12f) {
                            extracted = p.toArgb();
                        }
                        if (extracted == 0 && wc.getSecondaryColor() != null) {
                            int sc = wc.getSecondaryColor().toArgb();
                            if (getSaturation(sc) >= 0.12f) extracted = sc;
                        }
                        if (extracted == 0 && wc.getTertiaryColor() != null) {
                            int tc = wc.getTertiaryColor().toArgb();
                            if (getSaturation(tc) >= 0.12f) extracted = tc;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        // 阶段 3：如果原生 API 允许，尝试直接获取壁纸 Drawable 采样
        if (extracted == 0) {
            try {
                WallpaperManager wm = WallpaperManager.getInstance(context);
                if (wm != null) {
                    Drawable d = wm.getDrawable();
                    if (d instanceof BitmapDrawable) {
                        Bitmap bmp = ((BitmapDrawable) d).getBitmap();
                        extracted = extractVibrantFromBitmap(bmp);
                    }
                }
            } catch (Throwable ignored) {}
        }

        // 阶段 4：如果依然未果，尝试 AOSP Accent 主色（API 31+）
        if (extracted == 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                int a1 = ContextCompat.getColor(context, android.R.color.system_accent1_500);
                if (getSaturation(a1) >= 0.12f) {
                    extracted = a1;
                }
            } catch (Throwable ignored) {}
        }

        // 阶段 5：保底活力翡翠绿（与护眼绿同系）
        if (extracted == 0) {
            extracted = Color.parseColor("#10B981");
        }

        sCachedSeedColor = extracted;
        return extracted;
    }

    /** 计算颜色饱和度 */
    private static float getSaturation(int color) {
        float[] hsl = new float[3];
        ColorUtils.colorToHSL(color, hsl);
        return hsl[1];
    }

    /** 对壁纸位图快速网格采样，抓取饱和度最高且明度适中的代表色 */
    private static int extractVibrantFromBitmap(Bitmap bmp) {
        if (bmp == null || bmp.getWidth() <= 0 || bmp.getHeight() <= 0) return 0;
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int stepX = Math.max(1, w / 24);
        int stepY = Math.max(1, h / 24);

        int bestColor = 0;
        float bestScore = -1f;
        float[] hsl = new float[3];

        for (int x = stepX / 2; x < w; x += stepX) {
            for (int y = stepY / 2; y < h; y += stepY) {
                int pixel = bmp.getPixel(x, y);
                ColorUtils.colorToHSL(pixel, hsl);
                float sat = hsl[1];
                float lum = hsl[2];
                if (lum >= 0.15f && lum <= 0.85f && sat >= 0.12f) {
                    float score = sat * 0.7f + (1.0f - Math.abs(lum - 0.5f) * 2f) * 0.3f;
                    if (score > bestScore) {
                        bestScore = score;
                        bestColor = pixel;
                    }
                }
            }
        }
        return bestColor;
    }

    /**
     * 根据深浅色模式、莫奈开关及不透明度，计算抽屉所需的一整套调色板。
     * 【关键规则】：打开反色（isDarkMode == true）之后，坚决不使用莫奈取色，保持纯正经典深色反色！
     */
    public static Palette resolve(Context ctx, boolean isDarkMode, boolean isMonet, int opacityPercent) {
        String style = "tonal_spot";
        String spec = "spec_2025";
        boolean invert = false;
        if (ctx != null) {
            try {
                com.deepseekharness.app.core.ConfigStore cfg = new com.deepseekharness.app.core.ConfigStore(ctx);
                style = cfg.getSheetPaletteStyle();
                spec = cfg.getSheetColorSpec();
                invert = cfg.isSheetMonetInvert();
            } catch (Throwable ignored) {}
        }
        return resolve(ctx, isDarkMode, isMonet, opacityPercent, style, spec, invert);
    }

    /**
     * 增强版调色板解析：支持色彩风格（PaletteStyle）与色彩标准（ColorSpec）
     * 仅在浅色模式且开启莫奈时生效；深色模式坚决不使用莫奈取色，保持经典深色反色。
     */
    public static Palette resolve(Context ctx, boolean isDarkMode, boolean isMonet, int opacityPercent,
                                  String paletteStyle, String colorSpec) {
        boolean invert = false;
        if (ctx != null) {
            try {
                invert = new com.deepseekharness.app.core.ConfigStore(ctx).isSheetMonetInvert();
            } catch (Throwable ignored) {}
        }
        return resolve(ctx, isDarkMode, isMonet, opacityPercent, paletteStyle, colorSpec, invert);
    }

    public static Palette resolve(Context ctx, boolean isDarkMode, boolean isMonet, int opacityPercent,
                                  String paletteStyle, String colorSpec, boolean invertMonet) {
        int opacity = opacityPercent;
        if (opacity < 30 || opacity > 100) {
            opacity = isDarkMode ? 80 : 88;
        }
        int alpha = (int) Math.round(opacity * 255.0 / 100.0);

        // 打开反色之后坚决不使用莫奈取色（或未开启莫奈）→ 严格使用经典方案
        if (isDarkMode || !isMonet || ctx == null) {
            if (isDarkMode) {
                // ================= 经典深色反色配色（科技蓝灰黑，极佳对比度） =================
                return new Palette(
                        Color.argb(alpha, 0x10, 0x14, 0x1B),
                        Color.parseColor("#8BA0B8"),
                        Color.parseColor("#56697E"),
                        Color.parseColor("#302A3344"),
                        Color.parseColor("#704A5568"),
                        Color.parseColor("#352A3344"),
                        "rgba(255, 255, 255, 0.06)",
                        "rgba(255, 255, 255, 0.12)",
                        "rgba(16, 20, 27, 0.96)",
                        "rgba(24, 29, 38, 0.96)",
                        "rgba(20, 24, 32, 0.97)",
                        "rgba(30, 36, 48, 0.96)",
                        "rgba(255, 255, 255, 0.12)",
                        "#8BA0B8",
                        "#56697E",
                        "#8BA0B8",
                        "#10141B"
                );
            } else {
                // ================= 经典浅色配色 =================
                return new Palette(
                        Color.argb(alpha, 0xF5, 0xF8, 0xFC),
                        Color.parseColor("#1A2230"),
                        Color.parseColor("#64748B"),
                        Color.parseColor("#30E2E6EE"),
                        Color.parseColor("#90CBD5E1"),
                        Color.parseColor("#35CBD5E1"),
                        "rgba(255, 255, 255, 0.75)",
                        "rgba(0, 0, 0, 0.08)",
                        "rgba(245, 248, 252, 0.97)",
                        "rgba(255, 255, 255, 0.98)",
                        "rgba(255, 255, 255, 0.98)",
                        "rgba(240, 243, 246, 0.96)",
                        "rgba(0, 0, 0, 0.08)",
                        "#1A2230",
                        "#4A5568",
                        "#1A2230",
                        "#F5F8FC"
                );
            }
        }

        // ================= 仅在浅色模式且开启莫奈时：根据风格与标准动态生成调色板 =================
        int seed = getWallpaperSeedColor(ctx);
        float[] seedHsl = new float[3];
        ColorUtils.colorToHSL(seed, seedHsl);
        float origH = seedHsl[0]; // 壁纸色相 0 ~ 360°
        float origS = seedHsl[1];
        float origL = seedHsl[2];

        String style = paletteStyle != null ? paletteStyle.toLowerCase(Locale.US) : "tonal_spot";
        boolean isSpec2025 = "spec_2025".equalsIgnoreCase(colorSpec);

        float h = origH;
        float cardSat = 0.28f;
        float cardLum = 0.95f;
        float textSat = 0.65f;
        float textLum = 0.16f;
        float textSubSat = 0.35f;
        float textSubLum = 0.42f;
        float brandSat = 0.85f;
        float brandLum = 0.36f;
        float handleSat = 0.36f;
        float borderSat = 0.32f;

        switch (style) {
            case "neutral":
                // 中性（Neutral）：近无彩色灰阶，极低饱和
                cardSat = 0.08f;
                cardLum = 0.96f;
                textSat = 0.15f;
                textLum = 0.15f;
                textSubSat = 0.10f;
                textSubLum = 0.44f;
                brandSat = 0.25f;
                brandLum = 0.35f;
                handleSat = 0.12f;
                borderSat = 0.10f;
                break;
            case "vibrant":
                // 鲜明（Vibrant）：高饱和鲜亮，张力十足
                cardSat = 0.42f;
                cardLum = 0.94f;
                textSat = 0.85f;
                textLum = 0.14f;
                textSubSat = 0.55f;
                textSubLum = 0.38f;
                brandSat = 0.95f;
                brandLum = 0.34f;
                handleSat = 0.50f;
                borderSat = 0.45f;
                break;
            case "expressive":
                // 表现力（Expressive）：色相旋转 +120°，现代跳跃色彩
                h = (origH + 120.0f) % 360.0f;
                cardSat = 0.35f;
                cardLum = 0.95f;
                textSat = 0.75f;
                textLum = 0.15f;
                textSubSat = 0.45f;
                textSubLum = 0.40f;
                brandSat = 0.90f;
                brandLum = 0.35f;
                handleSat = 0.42f;
                borderSat = 0.38f;
                break;
            case "rainbow":
                // 彩虹（Rainbow）：底板清爽透光，强调色纯净宽色域
                cardSat = 0.18f;
                cardLum = 0.96f;
                textSat = 0.70f;
                textLum = 0.15f;
                textSubSat = 0.40f;
                textSubLum = 0.42f;
                brandSat = 0.92f;
                brandLum = 0.35f;
                handleSat = 0.32f;
                borderSat = 0.28f;
                break;
            case "fruit_salad":
                // 水果沙拉（Fruit Salad）：色相微偏 -50°，鲜活清爽果色
                h = (origH - 50.0f + 360.0f) % 360.0f;
                cardSat = 0.36f;
                cardLum = 0.94f;
                textSat = 0.80f;
                textLum = 0.15f;
                textSubSat = 0.50f;
                textSubLum = 0.39f;
                brandSat = 0.90f;
                brandLum = 0.34f;
                handleSat = 0.42f;
                borderSat = 0.38f;
                break;
            case "monochrome":
                // 单色（Monochrome）：纯黑白灰阶，饱和度全清零
                h = 0.0f;
                cardSat = 0.0f;
                cardLum = 0.96f;
                textSat = 0.0f;
                textLum = 0.12f;
                textSubSat = 0.0f;
                textSubLum = 0.45f;
                brandSat = 0.0f;
                brandLum = 0.20f;
                handleSat = 0.0f;
                borderSat = 0.0f;
                break;
            case "fidelity":
                // 保真（Fidelity）：忠实原始壁纸色彩饱和度
                cardSat = Math.max(0.10f, Math.min(0.50f, origS * 0.6f));
                cardLum = 0.95f;
                textSat = Math.max(0.30f, Math.min(0.90f, origS * 1.2f));
                textLum = 0.15f;
                textSubSat = Math.max(0.20f, Math.min(0.70f, origS * 0.8f));
                textSubLum = 0.40f;
                brandSat = Math.max(0.40f, Math.min(0.95f, origS * 1.4f));
                brandLum = 0.35f;
                handleSat = cardSat * 1.3f;
                borderSat = cardSat * 1.1f;
                break;
            case "tonal_spot":
            default:
                // 色调点（Tonal Spot）：默认均衡标准
                cardSat = 0.28f;
                cardLum = 0.95f;
                textSat = 0.65f;
                textLum = 0.16f;
                textSubSat = 0.35f;
                textSubLum = 0.42f;
                brandSat = 0.85f;
                brandLum = 0.36f;
                handleSat = 0.36f;
                borderSat = 0.32f;
                break;
        }

        // 若采用 Material 3 Expressive 2025 规范：拉大浅色下对比度跨度，文字更深凝练，微增强调色
        if (isSpec2025 && !style.equals("monochrome")) {
            textLum = Math.max(0.11f, textLum - 0.02f);
            brandSat = Math.min(1.0f, brandSat * 1.05f);
        }

        int brand;
        int handle;
        int border;
        int line;
        int lightCardRgb;
        int inputInner;
        int inputBorderColor;
        int menuInner;
        int text;
        int textSecondary;

        if (invertMonet) {
            int[] preview = SheetSettingsFragment.getStylePreviewColors(style, seed, true);
            int primary = (preview != null && preview.length > 0) ? preview[0] : ColorUtils.HSLToColor(new float[]{h, brandSat, brandLum});
            int container = (preview != null && preview.length > 1) ? preview[1] : ColorUtils.HSLToColor(new float[]{h, cardSat, cardLum});

            // 取反后：三色倒转，倒序首位（清新明亮的高亮点缀色）跃升为拖拽条与品牌核心主强调色
            brand = primary;
            handle = primary;

            // 抽屉四周描边与内部分割线：采用倒序主色半透微光，视觉辨识度极高
            border = Color.argb(0x55, Color.red(primary), Color.green(primary), Color.blue(primary));
            line = Color.argb(0x35, Color.red(primary), Color.green(primary), Color.blue(primary));

            // 卡片底色微混通透容器色
            lightCardRgb = ColorUtils.blendARGB(0xFFF6F8FB, container, 0.20f);

            // 前端输入框与菜单底板同步融入容器过渡色，描边高光匹配主色
            inputInner = ColorUtils.blendARGB(0xFFFFFFFF, container, 0.14f);
            inputBorderColor = ColorUtils.setAlphaComponent(primary, 0x45);
            menuInner = ColorUtils.blendARGB(0xFFFFFFFF, container, 0.18f);

            text = ColorUtils.blendARGB(0xFF1A2230, primary, 0.18f);
            textSecondary = ColorUtils.blendARGB(0xFF64748B, primary, 0.22f);
        } else {
            // 未取反时：100% 保持经典原版算法
            lightCardRgb = ColorUtils.HSLToColor(new float[]{h, cardSat, cardLum});
            text = ColorUtils.HSLToColor(new float[]{h, textSat, textLum});
            textSecondary = ColorUtils.HSLToColor(new float[]{h, textSubSat, textSubLum});
            brand = ColorUtils.HSLToColor(new float[]{h, brandSat, brandLum});
            handle = ColorUtils.HSLToColor(new float[]{h, handleSat, 0.72f});

            int borderRaw = ColorUtils.HSLToColor(new float[]{h, borderSat, 0.82f});
            line = Color.argb(0x40, Color.red(borderRaw), Color.green(borderRaw), Color.blue(borderRaw));
            border = Color.argb(0x45, Color.red(borderRaw), Color.green(borderRaw), Color.blue(borderRaw));

            inputInner = ColorUtils.HSLToColor(new float[]{h, Math.max(0f, cardSat - 0.06f), 0.98f});
            inputBorderColor = ColorUtils.HSLToColor(new float[]{h, borderSat, 0.80f});
            menuInner = ColorUtils.HSLToColor(new float[]{h, Math.max(0f, cardSat - 0.02f), 0.97f});
        }

        int cardBg = Color.argb(alpha, Color.red(lightCardRgb), Color.green(lightCardRgb), Color.blue(lightCardRgb));

        return new Palette(
                cardBg, text, textSecondary, line, handle, border,
                toRgbaString(inputInner, 0.75f),
                toRgbaString(inputBorderColor, 0.35f),
                toRgbaString(lightCardRgb, 0.97f),
                toRgbaString(menuInner, 0.98f),
                toRgbaString(menuInner, 0.98f),
                toRgbaString(lightCardRgb, 0.96f),
                toRgbaString(inputBorderColor, 0.35f),
                toHexString(text),
                toHexString(textSecondary),
                toHexString(brand),
                toHexString(lightCardRgb)
        );
    }
}
