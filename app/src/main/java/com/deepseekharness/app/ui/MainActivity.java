package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * 主界面外壳：启动门禁 + 底部导航（启动 / 插件 / 设置 / 终端）+ 顶栏标题 + 关于入口。
 */
public class MainActivity extends AppCompatActivity {

    public static volatile MainActivity current;
    private boolean requestingLocalNetwork;
    private final androidx.activity.result.ActivityResultLauncher<String> localNetworkPermission =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
                    granted -> {
                        requestingLocalNetwork = false;
                        if (granted) com.deepseekharness.app.bridge.LocalNetworkAccess.applyConfiguredFeatures(this);
                        else android.widget.Toast.makeText(this,
                                "未允许局域网访问；本机对话仍可使用，LAN / 无线 ADB 需在系统权限设置中开启",
                                android.widget.Toast.LENGTH_LONG).show();
                    });

    public void requestLocalNetwork() {
        if (com.deepseekharness.app.bridge.LocalNetworkAccess.granted(this)) {
            com.deepseekharness.app.bridge.LocalNetworkAccess.applyConfiguredFeatures(this);
        } else if (!requestingLocalNetwork) {
            requestingLocalNetwork = true;
            getSharedPreferences(com.deepseekharness.app.util.Constants.PREFS, MODE_PRIVATE)
                    .edit().putBoolean("local_network_permission_asked", true).apply();
            localNetworkPermission.launch(com.deepseekharness.app.bridge.LocalNetworkAccess.PERMISSION);
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        if (!isTaskRoot()) {
            Intent intent = getIntent();
            String action = intent != null ? intent.getAction() : null;
            if (intent != null && intent.hasCategory(Intent.CATEGORY_LAUNCHER) && Intent.ACTION_MAIN.equals(action)) {
                finish();
                return;
            }
        }
        super.onCreate(savedInstanceState);
        current = this;

        ConfigStore config = new ConfigStore(this);
        HarnessController controller = HarnessController.get(this);
        boolean skipExtract = getIntent().getBooleanExtra("skip_extract", false);

        // 启动门禁：未欢迎 → Welcome
        if (!config.isWelcomed()) {
            startActivity(new Intent(this, WelcomeActivity.class));
            finish();
            return;
        }

        // Android 13+ (API 33+) 动态申请通知权限，避免重装后系统默认禁用导致通知与胶囊彻底哑火
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }

        setContentView(R.layout.activity_main);

        // 1:1 对齐 HTML .aurora { filter: blur(100px); } 硬件加速高斯物理漫射
        android.view.View auroraView = findViewById(R.id.global_aurora);
        if (auroraView != null && Build.VERSION.SDK_INT >= 31) {
            float blurPx = 80f * getResources().getDisplayMetrics().density;
            try {
                auroraView.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                        blurPx, blurPx, android.graphics.Shader.TileMode.CLAMP));
            } catch (Throwable ignored) { }
        }

        String pendingLink = getSharedPreferences("dsha-install-link", MODE_PRIVATE).getString("pending", "");
        if (!pendingLink.isEmpty()) {
            getSharedPreferences("dsha-install-link", MODE_PRIVATE).edit().remove("pending").apply();
            try {
                com.deepseekharness.app.util.PluginInstallLink.parse(pendingLink);
                startActivity(new Intent(this, PluginInstallActivity.class).setData(android.net.Uri.parse(pendingLink)));
            } catch (IllegalArgumentException ignored) { }
        }

        TextView title = findViewById(R.id.app_title);
        View themeBtn = findViewById(R.id.btn_theme);
        ImageView themeIcon = findViewById(R.id.img_theme_icon);
        if (themeBtn != null && themeIcon != null) {
            boolean dark = ThemeController.isDark(this);
            themeIcon.setImageResource(dark ? R.drawable.ic_moon : R.drawable.ic_sun);
            themeIcon.setContentDescription(dark ? "夜间模式" : "日间模式");
            themeBtn.setOnClickListener(v -> ThemeController.toggle(this));
        }

        // 全局 Fragment 视图创建莫奈自动注入 (杜绝切页/切 Tab 莫奈失效)
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(
                new androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentViewCreated(
                            @androidx.annotation.NonNull androidx.fragment.app.FragmentManager fm,
                            @androidx.annotation.NonNull androidx.fragment.app.Fragment f,
                            @androidx.annotation.NonNull View v,
                            @androidx.annotation.Nullable Bundle savedInstanceState) {
                        MonetEngine.applyToViewTree(v, MonetEngine.resolveCurrentPalette(MainActivity.this));
                    }
                }, true);

        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        nav.setOnItemSelectedListener(item -> {
            // 关键安全守卫：切换顶层 Tab 时，坚决清空 FragmentManager 所有二级页面返回栈，彻底杜绝跨 Tab 返回泄漏与幽灵 Fragment 叠加
            if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
                getSupportFragmentManager().popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
            }

            Fragment f;
            int id = item.getItemId();
            if (id == R.id.nav_launch) {
                f = new LaunchFragment();
                title.setText(R.string.nav_launch);
            } else if (id == R.id.nav_plugins) {
                f = new PluginFragment();
                if (getIntent().getBooleanExtra("open_plugins", false)) {
                    Bundle args = new Bundle(); args.putBoolean("show_installed", true); f.setArguments(args);
                    getIntent().removeExtra("open_plugins");
                }
                title.setText(R.string.nav_plugins);
            } else if (id == R.id.nav_settings) {
                f = new SettingsFragment();
                title.setText(R.string.nav_settings);
            } else {
                // 终端：默认挂真 PTY 页（vim/htop/tmux 能跑），可在 PTY 页切回简易版
                f = PtyTerminalFragment.preferred(this)
                        ? new PtyTerminalFragment() : new TerminalFragment();
                title.setText(R.string.nav_terminal);
            }
            getSupportFragmentManager().beginTransaction()
                    .setCustomAnimations(
                            R.anim.fragment_enter, R.anim.fragment_exit,
                            R.anim.fragment_pop_enter, R.anim.fragment_pop_exit)
                    .replace(R.id.fragment_container, f)
                    .commit();
            return true;
        });

        // 重选当前 Tab 监听：如果在设置二级页面重复点击设置 Tab，立即回滚返回栈回到设置首页
        nav.setOnItemReselectedListener(item -> {
            if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
                getSupportFragmentManager().popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
                if (item.getItemId() == R.id.nav_settings) {
                    title.setText(R.string.nav_settings);
                }
            }
        });

        // 统一全局系统侧滑返回手势拦截：
        // 1. 若当前在二级子页面（设置子页等），正常退回设置页
        // 2. 若当前在顶层非首页 Tab（插件、设置首页、终端），侧滑返回平滑导航回【启动】首页
        // 3. 若已在【启动】首页，侧滑返回退出应用
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                FragmentManager fm = getSupportFragmentManager();
                if (fm.getBackStackEntryCount() > 0) {
                    // 若有二级页面在栈中，安全回退
                    fm.popBackStack();
                    return;
                }
                int currentTab = nav.getSelectedItemId();
                if (currentTab != R.id.nav_launch) {
                    nav.setSelectedItemId(R.id.nav_launch);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        if (savedInstanceState == null) {
            if (getIntent().getBooleanExtra("open_terminal", false)) {
                nav.setSelectedItemId(R.id.nav_terminal);
            } else if (getIntent().getBooleanExtra("open_plugins", false)) {
                nav.setSelectedItemId(R.id.nav_plugins);
            } else {
                nav.setSelectedItemId(R.id.nav_launch);
            }
        } else {
            // 重建时（如切换主题），根据当前恢复的 tab 状态同步更新标题，避免错乱停留在“启动”
            int selectedId = nav.getSelectedItemId();
            if (selectedId == R.id.nav_plugins) {
                title.setText(R.string.nav_plugins);
            } else if (selectedId == R.id.nav_settings) {
                title.setText(R.string.nav_settings);
            } else if (selectedId == R.id.nav_terminal) {
                title.setText(R.string.nav_terminal);
            } else {
                title.setText(R.string.nav_launch);
            }
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        if (nav != null) {
            if (intent.getBooleanExtra("open_terminal", false)) {
                nav.setSelectedItemId(R.id.nav_terminal);
            } else if (intent.getBooleanExtra("open_plugins", false)) {
                nav.setSelectedItemId(R.id.nav_plugins);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        com.deepseekharness.app.HarnessService.checkAndSyncService(this);
        if (!isFinishing() && findViewById(R.id.bottom_nav) != null
                && new ConfigStore(this).isLanMode()
                && !com.deepseekharness.app.bridge.LocalNetworkAccess.granted(this)
                && !getSharedPreferences(com.deepseekharness.app.util.Constants.PREFS, MODE_PRIVATE)
                .getBoolean("local_network_permission_asked", false)) requestLocalNetwork();
    }

    @Override
    protected void onDestroy() {
        if (current == this) current = null;
        if (isFinishing()) {
            // 仅在 Activity 真正关闭退出时收掉 PTY 会话与简易 shell（防在容器里留孤儿 bash）
            // 因主题切换或旋转屏幕触发的 recreate 不应杀灭用户正在运行的终端任务！
            try {
                PtyTerminalFragment.shutdown();
            } catch (Throwable ignored) {
            }
            try {
                TerminalFragment.shutdownShell();
            } catch (Throwable ignored) {
            }
        }
        super.onDestroy();
    }

    public static void start(Context ctx) {
        ctx.startActivity(new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }
}
