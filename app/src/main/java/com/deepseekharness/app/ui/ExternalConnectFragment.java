package com.deepseekharness.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.content.res.ColorStateList;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import java.net.InetAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.R;
import com.deepseekharness.app.core.AdbManager;
import com.deepseekharness.app.util.ToastHelper;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 外部连接二级管理页面：
 * 1. 纯 Skia 现代卡片规范 (ModernCardView + DshaToggle + ModernSegmentedView)；
 * 2. 深度接入 MonetEngine 莫奈色彩体系；
 * 3. 严格禁止任何 emoji 字符，保证原生专业质感；
 * 4. 支持有线调试检测、无线调试开关及连接串、权限模式调节与外部设备主动互联。
 */
public final class ExternalConnectFragment extends Fragment {

    private final AdbManager adbManager = AdbManager.get();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // UI 组件引用
    private ImageView btnBack;
    private TextView tvUsbStatus;
    private TextView btnCheckUsb;

    private TextView tvWirelessStatus;
    private DshaToggle toggleWireless;
    private LinearLayout layoutWirelessInfo;
    private TextView tvConnectCmd;
    private TextView btnCopyCmd;
    private LinearLayout layoutUsbInfo;
    private TextView btnCopyUsbShell;
    private TextView btnCopyUsbScrcpy;
    private TextView btnCopyWirelessShell;
    private TextView btnCopyWirelessScrcpy;

    private TextView tvPermissionDesc;
    private ModernSegmentedView segPermission;
    private DshaToggle toggleChrootDirect;
    private View rowChrootDirect;

    private EditText etTarget;
    private TextView btnConnect;
    private TextView btnRefreshDevices;
    private TextView tvEmptyDevices;
    private LinearLayout layoutDevices;

    private boolean isUpdatingUi = false;
    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener pairingDiscoveryListener;
    private NsdManager.DiscoveryListener connectDiscoveryListener;
    private final Map<String, DiscoveredDevice> discoveredDevices = new ConcurrentHashMap<>();

    public static class DiscoveredDevice {
        public final String name;
        public final String host;
        public final int port;
        public final boolean isPairing;

        public DiscoveredDevice(String name, String host, int port, boolean isPairing) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.isPairing = isPairing;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_external_connect, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        // 拦截系统返回手势
        requireActivity().getOnBackPressedDispatcher().addCallback(
                getViewLifecycleOwner(),
                new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        getParentFragmentManager().popBackStack();
                    }
                }
        );

        syncActivityTitle(true);
        initViews(v);
        applyTheme(v);
        bindEvents();
        refreshAllAsync();
    }

    private void initViews(View v) {
        btnBack = v.findViewById(R.id.ext_btn_back);
        tvUsbStatus = v.findViewById(R.id.ext_tv_usb_status);
        btnCheckUsb = v.findViewById(R.id.ext_btn_check_usb);

        tvWirelessStatus = v.findViewById(R.id.ext_tv_wireless_status);
        toggleWireless = v.findViewById(R.id.ext_toggle_wireless);
        layoutWirelessInfo = v.findViewById(R.id.ext_layout_wireless_info);
        tvConnectCmd = v.findViewById(R.id.ext_tv_connect_cmd);
        btnCopyCmd = v.findViewById(R.id.ext_btn_copy_cmd);
        layoutUsbInfo = v.findViewById(R.id.ext_layout_usb_info);
        btnCopyUsbShell = v.findViewById(R.id.ext_btn_copy_usb_shell);
        btnCopyUsbScrcpy = v.findViewById(R.id.ext_btn_copy_usb_scrcpy);
        btnCopyWirelessShell = v.findViewById(R.id.ext_btn_copy_wireless_shell);
        btnCopyWirelessScrcpy = v.findViewById(R.id.ext_btn_copy_wireless_scrcpy);

        tvPermissionDesc = v.findViewById(R.id.ext_tv_permission_desc);
        segPermission = v.findViewById(R.id.ext_seg_permission);
        if (segPermission != null) {
            segPermission.setTabs("普通权限 (Shell)", "最高权限 (Root)");
        }
        toggleChrootDirect = v.findViewById(R.id.ext_toggle_chroot_direct);
        rowChrootDirect = v.findViewById(R.id.ext_row_chroot_direct);

        etTarget = v.findViewById(R.id.ext_et_target);
        btnConnect = v.findViewById(R.id.ext_btn_connect);
        btnRefreshDevices = v.findViewById(R.id.ext_btn_refresh_devices);
        tvEmptyDevices = v.findViewById(R.id.ext_tv_empty_devices);
        layoutDevices = v.findViewById(R.id.ext_layout_devices);
    }

    private void applyTheme(View root) {
        if (!isAdded()) return;
        Context ctx = requireContext();
        MonetEngine.PaletteInfo palette = MonetEngine.resolveCurrentPalette(ctx);
        MonetEngine.applyToViewTree(root, palette);

        if (segPermission != null) {
            segPermission.setPrimaryColor(palette.primaryColor);
        }
        if (btnConnect != null && palette.isMonetActive) {
            btnConnect.setBackgroundTintList(ColorStateList.valueOf(palette.primaryColor));
        }
    }

    private void bindEvents() {
        if (btnBack != null) {
            btnBack.setOnClickListener(x -> getParentFragmentManager().popBackStack());
        }

        // 有线 USB 检测
        if (btnCheckUsb != null) {
            btnCheckUsb.setOnClickListener(x -> checkUsbAsync(true));
        }

        // 无线开关
        if (toggleWireless != null) {
            toggleWireless.setOnCheckedChangeListener((btn, isChecked) -> {
                if (isUpdatingUi) return;
                toggleWirelessAsync(isChecked);
            });
        }

        // 复制命令
        if (btnCopyCmd != null) {
            btnCopyCmd.setOnClickListener(x -> {
                if (tvConnectCmd != null) copyToClipboard(tvConnectCmd.getText().toString());
            });
        }
        if (btnCopyUsbShell != null) {
            btnCopyUsbShell.setOnClickListener(x -> copyToClipboard("adb -d shell"));
        }
        if (btnCopyUsbScrcpy != null) {
            btnCopyUsbScrcpy.setOnClickListener(x -> copyToClipboard("scrcpy -d"));
        }
        if (btnCopyWirelessShell != null) {
            btnCopyWirelessShell.setOnClickListener(x -> copyToClipboard("adb shell"));
        }
        if (btnCopyWirelessScrcpy != null) {
            btnCopyWirelessScrcpy.setOnClickListener(x -> {
                AdbManager.WirelessStatus st = adbManager.getWirelessStatus();
                copyToClipboard("scrcpy -s " + st.ipAddress + ":" + st.port);
            });
        }

        // 权限切换
        if (segPermission != null) {
            segPermission.setOnTabSelectedListener(index -> {
                if (isUpdatingUi) return;
                switchPermissionAsync(index);
            });
        }

        // 直连 chroot 容器终端
        if (toggleChrootDirect != null) {
            toggleChrootDirect.setOnCheckedChangeListener((btn, isChecked) -> {
                if (isUpdatingUi) return;
                toggleChrootDirectAsync(isChecked);
            });
        }
        if (rowChrootDirect != null) {
            rowChrootDirect.setOnClickListener(x -> {
                if (toggleChrootDirect != null) toggleChrootDirect.toggle();
            });
        }

        // 主动连接
        if (btnConnect != null) {
            btnConnect.setOnClickListener(x -> connectRemoteAsync());
        }

        // 刷新设备列表
        if (btnRefreshDevices != null) {
            btnRefreshDevices.setOnClickListener(x -> refreshDevicesAsync(true));
        }
    }

    /** 异步全局刷新 */
    private void refreshAllAsync() {
        executor.execute(() -> {
            AdbManager.UsbStatus usb = adbManager.getUsbStatus();
            AdbManager.WirelessStatus wireless = adbManager.getWirelessStatus();
            int permMode = adbManager.getPermissionMode();
            boolean chrootDirect = adbManager.isChrootDirectEnabled();
            List<AdbManager.AdbDevice> devices = adbManager.getConnectedDevices();

            mainHandler.post(() -> {
                if (!isAdded()) return;
                isUpdatingUi = true;
                try {
                    // 1. USB 状态
                    if (tvUsbStatus != null) {
                        tvUsbStatus.setText(usb.stateDescription);
                    }
                    if (layoutUsbInfo != null) {
                        layoutUsbInfo.setVisibility(usb.isConnected && usb.adbEnabled ? View.VISIBLE : View.GONE);
                    }

                    // 2. 无线状态
                    if (toggleWireless != null) {
                        toggleWireless.setChecked(wireless.isEnabled, false, false);
                    }
                    if (tvWirelessStatus != null) {
                        if (wireless.isEnabled) {
                            if (!wireless.inboundClients.isEmpty()) {
                                tvWirelessStatus.setText("正在监听 " + wireless.port + " 端口 (已连入: " + String.join(", ", wireless.inboundClients) + ")");
                            } else {
                                tvWirelessStatus.setText("正在监听 " + wireless.port + " 端口 (" + wireless.ipAddress + ")");
                            }
                        } else {
                            tvWirelessStatus.setText("未开启 (仅允许有线连接)");
                        }
                    }
                    if (layoutWirelessInfo != null) {
                        layoutWirelessInfo.setVisibility(wireless.isEnabled ? View.VISIBLE : View.GONE);
                    }
                    if (tvConnectCmd != null) {
                        tvConnectCmd.setText(wireless.connectCommand);
                    }

                    // 3. 权限模式 (0: Shell, 1: Root)
                    if (segPermission != null) {
                        segPermission.setSelectedIndex(permMode, false);
                    }
                    updatePermissionDescText(permMode);

                    // 3.5 直连 chroot 模式
                    if (toggleChrootDirect != null) {
                        toggleChrootDirect.setChecked(chrootDirect, false, false);
                    }

                    // 4. 设备列表
                    renderDeviceList(devices);
                } finally {
                    isUpdatingUi = false;
                }
            });
        });
    }

    private void checkUsbAsync(boolean showToast) {
        if (tvUsbStatus != null) tvUsbStatus.setText("检测状态中...");
        executor.execute(() -> {
            AdbManager.UsbStatus usb = adbManager.getUsbStatus();
            mainHandler.post(() -> {
                if (!isAdded()) return;
                if (tvUsbStatus != null) {
                    tvUsbStatus.setText(usb.stateDescription);
                }
                if (showToast) {
                    ToastHelper.show(requireContext(), "USB 状态：" + usb.stateDescription);
                }
            });
        });
    }

    private void toggleWirelessAsync(boolean enable) {
        if (tvWirelessStatus != null) {
            tvWirelessStatus.setText(enable ? "正在开启无线调试..." : "正在关闭无线调试...");
        }
        executor.execute(() -> {
            boolean success = adbManager.setWirelessEnabled(enable);
            AdbManager.WirelessStatus status = adbManager.getWirelessStatus();
            mainHandler.post(() -> {
                if (!isAdded()) return;
                isUpdatingUi = true;
                try {
                    if (toggleWireless != null) {
                        toggleWireless.setChecked(status.isEnabled, false, false);
                    }
                    if (tvWirelessStatus != null) {
                        if (status.isEnabled) {
                            tvWirelessStatus.setText("正在监听 " + status.port + " 端口 (" + status.ipAddress + ")");
                        } else {
                            tvWirelessStatus.setText("未开启 (仅允许有线连接)");
                        }
                    }
                    if (layoutWirelessInfo != null) {
                        layoutWirelessInfo.setVisibility(status.isEnabled ? View.VISIBLE : View.GONE);
                    }
                    if (tvConnectCmd != null) {
                        tvConnectCmd.setText(status.connectCommand);
                    }
                    ToastHelper.show(requireContext(), status.isEnabled ? "无线调试已就绪" : "无线调试已关闭");
                } finally {
                    isUpdatingUi = false;
                }
            });
        });
    }

    private void switchPermissionAsync(int mode) {
        updatePermissionDescText(mode);
        executor.execute(() -> {
            boolean success = adbManager.setPermissionMode(mode);
            int current = adbManager.getPermissionMode();
            mainHandler.post(() -> {
                if (!isAdded()) return;
                isUpdatingUi = true;
                try {
                    if (segPermission != null) {
                        segPermission.setSelectedIndex(current, false);
                    }
                    updatePermissionDescText(current);
                    String label = (current == 1) ? "最高权限 (Root UID 0)" : "普通权限 (Shell UID 2000)";
                    ToastHelper.show(requireContext(), "已应用：" + label);
                } finally {
                    isUpdatingUi = false;
                }
            });
        });
    }

    private void toggleChrootDirectAsync(boolean enable) {
        executor.execute(() -> {
            boolean success = adbManager.setChrootDirectEnabled(enable);
            boolean current = adbManager.isChrootDirectEnabled();
            mainHandler.post(() -> {
                if (!isAdded()) return;
                isUpdatingUi = true;
                try {
                    if (toggleChrootDirect != null) {
                        toggleChrootDirect.setChecked(current, false, false);
                    }
                    String msg = current ? "已开启直连 Linux 容器终端模式" : "已恢复为 Android 宿主 Shell 模式";
                    ToastHelper.show(requireContext(), msg);
                } finally {
                    isUpdatingUi = false;
                }
            });
        });
    }

    private void updatePermissionDescText(int mode) {
        if (tvPermissionDesc == null) return;
        if (mode == 1) {
            tvPermissionDesc.setText("当前模式：最高权限 (Root)，连入直接持有 UID 0");
        } else {
            tvPermissionDesc.setText("当前模式：普通权限 (Shell)，连入受限于 UID 2000");
        }
    }

    private void connectRemoteAsync() {
        if (etTarget == null) return;
        String target = etTarget.getText().toString().trim();
        if (TextUtils.isEmpty(target)) {
            ToastHelper.show(requireContext(), "请输入目标设备地址");
            return;
        }

        btnConnect.setEnabled(false);
        btnConnect.setText("正在连接...");
        executor.execute(() -> {
            String result = adbManager.connectRemote(target);
            List<AdbManager.AdbDevice> devices = adbManager.getConnectedDevices();
            mainHandler.post(() -> {
                if (!isAdded()) return;
                btnConnect.setEnabled(true);
                btnConnect.setText("连接设备");
                ToastHelper.show(requireContext(), result.isEmpty() ? "连接指令已发送" : result);
                renderDeviceList(devices);
            });
        });
    }

    private void refreshDevicesAsync(boolean showToast) {
        startNsdDiscovery();
        if (btnRefreshDevices != null) btnRefreshDevices.setText("刷新中...");
        executor.execute(() -> {
            List<AdbManager.AdbDevice> devices = adbManager.getConnectedDevices();
            mainHandler.post(() -> {
                if (!isAdded()) return;
                if (btnRefreshDevices != null) btnRefreshDevices.setText("识别与刷新");
                renderDeviceList(devices);
                if (showToast) {
                    ToastHelper.show(requireContext(), "设备列表已刷新 (" + devices.size() + "台)");
                }
            });
        });
    }

    private void disconnectRemoteAsync(String target) {
        executor.execute(() -> {
            String result = adbManager.disconnectRemote(target);
            mainHandler.post(() -> {
                if (!isAdded()) return;
                ToastHelper.show(requireContext(), result.isEmpty() ? "已断开连接" : result);
                refreshDevicesAsync(false);
            });
        });
    }

    /** 动态渲染设备列表 */
    private void renderDeviceList(List<AdbManager.AdbDevice> devices) {
        if (layoutDevices == null || tvEmptyDevices == null) return;
        layoutDevices.removeAllViews();

        boolean hasDiscovered = (discoveredDevices != null && !discoveredDevices.isEmpty());
        boolean hasConnected = (devices != null && !devices.isEmpty());

        if (!hasDiscovered && !hasConnected) {
            tvEmptyDevices.setVisibility(View.VISIBLE);
            return;
        }

        tvEmptyDevices.setVisibility(View.GONE);
        Context ctx = requireContext();
        float density = ctx.getResources().getDisplayMetrics().density;

        // 先渲染局域网通过 mDNS 发现的设备
        for (DiscoveredDevice disc : discoveredDevices.values()) {
            LinearLayout item = new LinearLayout(ctx);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            int py = Math.round(10f * density);
            item.setPadding(0, py, 0, py);

            LinearLayout infoBox = new LinearLayout(ctx);
            infoBox.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams lpInfo = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            infoBox.setLayoutParams(lpInfo);

            TextView tvAddress = new TextView(ctx);
            tvAddress.setText(disc.name + " (" + disc.host + ":" + disc.port + ")");
            tvAddress.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tvAddress.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            tvAddress.setTextColor(ctx.getColor(R.color.text));
            infoBox.addView(tvAddress);

            TextView tvState = new TextView(ctx);
            tvState.setText(disc.isPairing ? "发现可配对设备 (点击配对)" : "发现可连接设备 (点击连接)");
            tvState.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tvState.setTextColor(ctx.getColor(R.color.primary));
            infoBox.addView(tvState);

            item.addView(infoBox);

            TextView btnAction = new TextView(ctx);
            btnAction.setText(disc.isPairing ? "配对" : "连接");
            btnAction.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            btnAction.setTextColor(ctx.getColor(R.color.accent_on));
            btnAction.setBackgroundResource(R.drawable.m3_btn_primary_expressive);
            btnAction.setGravity(Gravity.CENTER);
            int px = Math.round(14f * density);
            int h = Math.round(30f * density);
            LinearLayout.LayoutParams lpBtn = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, h);
            btnAction.setLayoutParams(lpBtn);
            btnAction.setPadding(px, 0, px, 0);
            btnAction.setClickable(true);
            btnAction.setFocusable(true);
            btnAction.setOnClickListener(x -> {
                if (disc.isPairing) {
                    showPairDialog(disc);
                } else {
                    if (etTarget != null) etTarget.setText(disc.host + ":" + disc.port);
                    connectRemoteAsync();
                }
            });

            item.addView(btnAction);
            layoutDevices.addView(item);
        }

        for (AdbManager.AdbDevice dev : devices) {
            LinearLayout item = new LinearLayout(ctx);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            int py = Math.round(10f * density);
            item.setPadding(0, py, 0, py);

            LinearLayout infoBox = new LinearLayout(ctx);
            infoBox.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams lpInfo = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            infoBox.setLayoutParams(lpInfo);

            TextView tvAddress = new TextView(ctx);
            tvAddress.setText(dev.address);
            tvAddress.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tvAddress.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
            tvAddress.setTextColor(ctx.getColor(R.color.text));
            infoBox.addView(tvAddress);

            TextView tvState = new TextView(ctx);
            String stateDesc = "device".equalsIgnoreCase(dev.state) ? "已连接并就绪" : ("状态: " + dev.state);
            if (!TextUtils.isEmpty(dev.extraInfo)) {
                stateDesc += " (" + dev.extraInfo + ")";
            }
            tvState.setText(stateDesc);
            tvState.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tvState.setTextColor(ctx.getColor("device".equalsIgnoreCase(dev.state) ? R.color.primary : R.color.text_muted));
            infoBox.addView(tvState);

            item.addView(infoBox);

            // 断开按钮
            TextView btnDisconnect = new TextView(ctx);
            btnDisconnect.setText("断开");
            btnDisconnect.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            btnDisconnect.setTextColor(ctx.getColor(R.color.err));
            btnDisconnect.setBackgroundResource(R.drawable.bg_btn_danger_small);
            btnDisconnect.setGravity(Gravity.CENTER);
            int px = Math.round(12f * density);
            int h = Math.round(30f * density);
            LinearLayout.LayoutParams lpBtn = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, h);
            btnDisconnect.setLayoutParams(lpBtn);
            btnDisconnect.setPadding(px, 0, px, 0);
            btnDisconnect.setClickable(true);
            btnDisconnect.setFocusable(true);
            btnDisconnect.setOnClickListener(x -> disconnectRemoteAsync(dev.address));

            item.addView(btnDisconnect);
            layoutDevices.addView(item);
        }
    }

    private void syncActivityTitle(boolean isSubpage) {
        if (!isAdded()) return;
        TextView activityTitle = requireActivity().findViewById(R.id.app_title);
        if (activityTitle != null) {
            activityTitle.setText(isSubpage ? "外部连接" : getString(R.string.nav_settings));
        }
    }

    private void startNsdDiscovery() {
        if (nsdManager == null) {
            try {
                nsdManager = (NsdManager) requireContext().getSystemService(Context.NSD_SERVICE);
            } catch (Throwable ignored) {}
        }
        if (nsdManager == null) return;

        stopNsdDiscovery();
        discoveredDevices.clear();

        // 1. 发现可配对设备 (_adb-tls-pairing._tcp)
        pairingDiscoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {}
            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {}
            @Override
            public void onDiscoveryStarted(String serviceType) {}
            @Override
            public void onDiscoveryStopped(String serviceType) {}
            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                resolveNsdService(serviceInfo, true);
            }
            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {}
        };

        // 2. 发现可直连设备 (_adb-tls-connect._tcp)
        connectDiscoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {}
            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {}
            @Override
            public void onDiscoveryStarted(String serviceType) {}
            @Override
            public void onDiscoveryStopped(String serviceType) {}
            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                resolveNsdService(serviceInfo, false);
            }
            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {}
        };

        try {
            nsdManager.discoverServices("_adb-tls-pairing._tcp", NsdManager.PROTOCOL_DNS_SD, pairingDiscoveryListener);
            nsdManager.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, connectDiscoveryListener);
        } catch (Throwable ignored) {}
    }

    private void resolveNsdService(NsdServiceInfo serviceInfo, boolean isPairing) {
        if (nsdManager == null) return;
        try {
            nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {}
                @Override
                public void onServiceResolved(NsdServiceInfo resolved) {
                    InetAddress host = resolved.getHost();
                    if (host != null) {
                        String ip = host.getHostAddress();
                        int port = resolved.getPort();
                        String name = resolved.getServiceName();
                        if (name == null || name.isEmpty()) name = "Android设备";
                        discoveredDevices.put(ip + ":" + port, new DiscoveredDevice(name, ip, port, isPairing));
                        mainHandler.post(() -> {
                            if (!isAdded()) return;
                            refreshAllAsync();
                        });
                    }
                }
            });
        } catch (Throwable ignored) {}
    }

    private void stopNsdDiscovery() {
        if (nsdManager != null) {
            try {
                if (pairingDiscoveryListener != null) {
                    nsdManager.stopServiceDiscovery(pairingDiscoveryListener);
                    pairingDiscoveryListener = null;
                }
                if (connectDiscoveryListener != null) {
                    nsdManager.stopServiceDiscovery(connectDiscoveryListener);
                    connectDiscoveryListener = null;
                }
            } catch (Throwable ignored) {}
        }
    }

    private void showPairDialog(DiscoveredDevice dev) {
        if (!isAdded()) return;
        Context ctx = requireContext();
        float density = ctx.getResources().getDisplayMetrics().density;

        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        int p = Math.round(18f * density);
        layout.setPadding(p, p, p, p);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText("配对设备: " + dev.name);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tvTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tvTitle.setTextColor(ctx.getColor(R.color.text));
        layout.addView(tvTitle);

        TextView tvSub = new TextView(ctx);
        tvSub.setText("目标地址: " + dev.host + ":" + dev.port + "\n请输入目标手机上显示的 6 位配对码");
        tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvSub.setTextColor(ctx.getColor(R.color.text_muted));
        tvSub.setPadding(0, Math.round(6f * density), 0, Math.round(12f * density));
        layout.addView(tvSub);

        EditText etCode = new EditText(ctx);
        etCode.setHint("6 位数字配对码");
        etCode.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etCode.setBackgroundResource(R.drawable.bg_input);
        etCode.setPadding(Math.round(14f * density), Math.round(10f * density), Math.round(14f * density), Math.round(10f * density));
        layout.addView(etCode);

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(ctx)
                .setView(layout)
                .setPositiveButton("配对并连接", null)
                .setNegativeButton("取消", null)
                .create();

        dialog.show();

        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String code = etCode.getText().toString().trim();
            if (code.length() < 6) {
                ToastHelper.show(ctx, "请输入完整的 6 位配对码");
                return;
            }
            dialog.dismiss();
            ToastHelper.show(ctx, "正在配对 " + dev.name + "...");
            executor.execute(() -> {
                String pairRes = adbManager.pairRemote(dev.host + ":" + dev.port, code);
                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    ToastHelper.show(requireContext(), pairRes.isEmpty() ? "配对请求已发送" : pairRes);
                    refreshAllAsync();
                });
            });
        });
    }

    private void copyToClipboard(String text) {
        if (TextUtils.isEmpty(text)) return;
        try {
            ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("ADB命令", text));
                ToastHelper.show(requireContext(), "连接命令已复制到剪贴板");
            }
        } catch (Throwable e) {
            ToastHelper.show(requireContext(), "复制失败");
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopNsdDiscovery();
        syncActivityTitle(false);
        executor.shutdown();
    }
}
