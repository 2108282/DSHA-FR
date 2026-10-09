package com.deepseekharness.app.viewer;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * 文件外部打开助手：生成受信任的 content:// URI 并呼出系统「打开方式」弹窗。
 * 针对 MT 管理器等文件管理工具附带物理路径 Extras 支持直接定位目录；
 * 针对系统 APK 安装器及第三方应用补全 ClipData 与批量 grantUriPermission 显式预授权。
 */
public final class FileOpenHelper {

    public static void openWithSystem(Context context, File file) {
        if (context == null || file == null || !file.exists()) {
            if (context != null) Toast.makeText(context, "文件不存在", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            // 规范化文件物理路径（解析软链接，如 /sdcard -> /storage/emulated/0）
            File realFile = file;
            try {
                realFile = file.getCanonicalFile();
            } catch (Exception ignored) {}
            if (!realFile.exists()) {
                realFile = file.getAbsoluteFile();
            }

            String mime = getMimeType(realFile.getName());
            Uri uri = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".updates",
                    realFile
            );

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mime);
            intent.addCategory(Intent.CATEGORY_DEFAULT);

            // 若是 APK 安装包，附带安装器关键信任标识
            if ("application/vnd.android.package-archive".equals(mime)) {
                intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
                intent.putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, context.getPackageName());
            }

            // 核心修复 1：绑定 ClipData，保障 Android 7.0+ 系统 Chooser 转发时完整继承 URI 临时授权
            intent.setClipData(ClipData.newRawUri("", uri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            // 核心修复 2：注入标准物理路径 Extras，让 MT 管理器、各类文件管理器及代码编辑器能够准确在目录树中高亮定位
            String absPath = realFile.getAbsolutePath();
            intent.putExtra("path", absPath);
            intent.putExtra("file_path", absPath);
            intent.putExtra("filePath", absPath);
            intent.putExtra("absolute_path", absPath);
            intent.putExtra("org.openintents.extra.ABSOLUTE_PATH", absPath);
            intent.putExtra(Intent.EXTRA_TEXT, absPath);

            // 核心修复 3：针对所有能响应该 Intent 的目标应用（包括系统 PackageInstaller 与第三方工具）批量显式预授权
            try {
                PackageManager pm = context.getPackageManager();
                if (pm != null) {
                    List<ResolveInfo> resInfoList = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
                    if (resInfoList != null) {
                        for (ResolveInfo resolveInfo : resInfoList) {
                            if (resolveInfo != null && resolveInfo.activityInfo != null) {
                                String packageName = resolveInfo.activityInfo.packageName;
                                context.grantUriPermission(packageName, uri,
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}

            Intent chooser = Intent.createChooser(intent, "打开方式");
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);
        } catch (Exception e) {
            Toast.makeText(context, "未找到支持打开此文件的应用", Toast.LENGTH_SHORT).show();
        }
    }

    public static String getMimeType(String fileName) {
        if (fileName == null) return "*/*";
        int idx = fileName.lastIndexOf('.');
        if (idx >= 0 && idx < fileName.length() - 1) {
            String ext = fileName.substring(idx + 1).toLowerCase(Locale.ROOT);
            switch (ext) {
                case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                case "xls": return "application/vnd.ms-excel";
                case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                case "doc": return "application/msword";
                case "pptx": return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
                case "ppt": return "application/vnd.ms-powerpoint";
                case "pdf": return "application/pdf";
                case "apk": return "application/vnd.android.package-archive";
                case "zip": return "application/zip";
                case "tar": return "application/x-tar";
                case "gz": case "tgz": return "application/gzip";
                case "png": return "image/png";
                case "jpg": case "jpeg": return "image/jpeg";
                case "webp": return "image/webp";
                case "gif": return "image/gif";
                case "txt": case "log": case "py": case "js": case "ts": case "json": case "yaml": case "yml": case "sh": case "java":
                    return "text/plain";
                default:
                    String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                    return mime != null ? mime : "*/*";
            }
        }
        return "*/*";
    }
}
