package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.deepseekharness.app.R;
import com.deepseekharness.app.core.PluginRepository;
import com.deepseekharness.app.ui.contract.PluginActions;
import com.deepseekharness.app.ui.contract.PluginPresenter;
import com.deepseekharness.app.ui.contract.PluginUiState;

import java.util.ArrayList;
import java.util.List;

/**
 * 插件页：负责 UI 表现层挂载与纯渲染，所有交互派发至 PluginActions 契约。
 */
public class PluginFragment extends Fragment implements PluginPresenter.ViewCallback {

    private PluginRepository repository;
    private PluginPresenter presenter;
    private PluginActions actions;

    private View root;
    private NestedScrollView pluginScroll;
    private ModernSegmentedView modernSegmentedTabs;
    private ImageView btnRefresh;
    private View pluginWebsiteSection;
    private Button btnPluginWebsite;
    private View pluginLinkSection;
    private EditText linkInput;
    private TextView linkHint;
    private TextView btnPluginPaste;
    private TextView btnPluginInstall;
    private TextView btnImport;
    private TextView btnExport;
    private TextView btnImportFallback;
    private ProgressBar pluginBusy;
    private TextView statusText;
    private View marketHelp;
    private View panelMarketContainer;
    private LinearLayout installedControls;
    private Button btnPluginUpdates;
    private EditText searchInput;
    private TextView btnOnlyCustom;
    private TextView pluginCount;
    private TextView btnSort;
    private TextView pluginEmpty;
    private RecyclerView pluginList;

    private final List<PluginRepository.Item> visibleItems = new ArrayList<>();
    private final Adapter adapter = new Adapter();

    private ArrayList<String> pendingExports = new ArrayList<>();
    private Uri pendingImport;
    private AlertDialog previewDialog;

    private final ActivityResultLauncher<String> readPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), allowed -> {
                Uri selected = pendingImport;
                pendingImport = null;
                if (allowed && selected != null) repository.importArchive(selected);
                else repository.selectionMessage("未获得文件读取权限，请改用系统文件选择器导入。");
            });

    private final ActivityResultLauncher<Intent> importPicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (repository == null) repository = new ViewModelProvider(requireActivity()).get(PluginRepository.class);
                Intent data = result.getData();
                Uri uri = data == null ? null : data.getData();
                if (uri == null && data != null && data.getClipData() != null && data.getClipData().getItemCount() > 0) {
                    uri = data.getClipData().getItemAt(0).getUri();
                }
                if (result.getResultCode() != android.app.Activity.RESULT_OK || uri == null) {
                    repository.selectionMessage("未选择文件或文件管理器未返回文件。可点「其他文件选择器」重试，选择 ZIP / TAR.GZ 插件包。");
                    return;
                }
                if (!"content".equals(uri.getScheme()) && !"file".equals(uri.getScheme())) {
                    repository.selectionMessage("文件管理器返回的地址无法读取，请改用系统文件选择器。");
                    return;
                }
                if ("file".equals(uri.getScheme()) && android.os.Build.VERSION.SDK_INT < 30
                        && requireContext().checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    pendingImport = uri;
                    readPermission.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE);
                    return;
                }
                repository.importArchive(uri);
            });

    private final ActivityResultLauncher<String> exportPicker = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/gzip"), uri -> {
                if (uri != null && !pendingExports.isEmpty()) {
                    repository.exportArchives(new ArrayList<>(pendingExports), uri);
                }
                pendingExports.clear();
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle state) {
        return inflater.inflate(R.layout.fragment_plugins, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle saved) {
        root = view;
        repository = new ViewModelProvider(requireActivity()).get(PluginRepository.class);
        presenter = new PluginPresenter(requireActivity(), repository, this);
        actions = presenter;

        // 1. 获取所有控件引用
        pluginScroll = view.findViewById(R.id.pluginScroll);
        modernSegmentedTabs = view.findViewById(R.id.modernSegmentedTabs);
        if (modernSegmentedTabs != null) {
            modernSegmentedTabs.setOnTabSelectedListener(index -> {
                if (actions != null) actions.onSelectTab(index == 0);
            });
        }
        btnRefresh = view.findViewById(R.id.btnRefresh);
        pluginWebsiteSection = view.findViewById(R.id.pluginWebsiteSection);
        btnPluginWebsite = view.findViewById(R.id.btnPluginWebsite);
        pluginLinkSection = view.findViewById(R.id.pluginLinkSection);
        linkInput = view.findViewById(R.id.appbar_github_input);
        linkHint = view.findViewById(R.id.pluginLinkHint);
        btnPluginPaste = view.findViewById(R.id.btnPluginPaste);
        btnPluginInstall = view.findViewById(R.id.btnPluginInstall);
        btnImport = view.findViewById(R.id.btnImport);
        btnExport = view.findViewById(R.id.btnExport);
        btnImportFallback = view.findViewById(R.id.btnImportFallback);
        if (btnImportFallback != null) {
            btnImportFallback.setPaintFlags(btnImportFallback.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        }
        pluginBusy = view.findViewById(R.id.pluginBusy);
        statusText = view.findViewById(R.id.statusText);
        marketHelp = view.findViewById(R.id.marketHelp);
        panelMarketContainer = view.findViewById(R.id.panelMarketContainer);
        installedControls = view.findViewById(R.id.installedControls);
        btnPluginUpdates = view.findViewById(R.id.btnPluginUpdates);
        searchInput = view.findViewById(R.id.pluginSearch);
        btnOnlyCustom = view.findViewById(R.id.btnOnlyCustom);
        pluginCount = view.findViewById(R.id.pluginCount);
        btnSort = view.findViewById(R.id.btnSort);
        pluginEmpty = view.findViewById(R.id.pluginEmpty);
        pluginList = view.findViewById(R.id.pluginList);

        pluginList.setLayoutManager(new LinearLayoutManager(requireContext()));
        pluginList.setNestedScrollingEnabled(false);
        pluginList.setItemAnimator(null);
        pluginList.setAdapter(adapter);

        // 2. 恢复状态或处理参数
        if (getArguments() != null && getArguments().getBoolean("show_installed", false)) {
            presenter.setMarket(false);
        }
        if (saved != null) {
            presenter.setMarket(saved.getBoolean("market", true));
            presenter.setEnabledFirst(saved.getBoolean("enabledFirst", false));
            ArrayList<String> names = saved.getStringArrayList("pendingExports");
            if (names != null) pendingExports = names;
            String imported = saved.getString("pendingImport");
            if (imported != null) pendingImport = Uri.parse(imported);
        }

        // 3. 事件契约绑定（单行派发，业务与视图完全解耦）
        btnRefresh.setOnClickListener(v -> actions.onRefreshClick());
        btnPluginWebsite.setOnClickListener(v -> actions.onWebsiteClick());
        btnPluginUpdates.setOnClickListener(v -> actions.onCheckUpdatesClick());
        btnPluginInstall.setOnClickListener(v -> actions.onInstallLinkClick());
        btnPluginPaste.setOnClickListener(v -> actions.onPasteLinkClick());
        btnImport.setOnClickListener(v -> actions.onImportClick(false));
        btnImportFallback.setOnClickListener(v -> actions.onImportClick(true));
        btnExport.setOnClickListener(v -> actions.onExportClick());
        btnSort.setOnClickListener(v -> actions.onToggleSortClick());
        statusText.setOnClickListener(v -> actions.onStatusMessageClick());

        if (btnOnlyCustom != null) {
            btnOnlyCustom.setOnClickListener(v -> {
                boolean currentHide = presenter != null && presenter.isHideBuiltin();
                if (actions != null) actions.onHideBuiltinChanged(!currentHide);
            });
        }

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                actions.onSearchQueryChanged(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        linkInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                actions.onLinkChanged(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        linkInput.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_UP)) {
                actions.onInstallLinkClick();
                return true;
            }
            return false;
        });

        // 4. 观察 Repository 状态流
        repository.state().observe(getViewLifecycleOwner(), state -> {
            presenter.updateRepoState(state);
            checkShowInstallPreview();
        });
        repository.preview().observe(getViewLifecycleOwner(), preview -> {
            if (preview != null) checkShowInstallPreview();
        });

        if (!repository.isBusy() && ((saved == null && getArguments() != null && getArguments().getBoolean("show_installed", false))
                || repository.state().getValue() == null
                || repository.state().getValue().items.isEmpty())) {
            repository.refresh();
        }

        presenter.recalculateState();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        if (presenter != null) {
            state.putBoolean("market", presenter.isMarket());
            state.putBoolean("enabledFirst", presenter.isEnabledFirst());
        }
        state.putStringArrayList("pendingExports", pendingExports);
        if (pendingImport != null) state.putString("pendingImport", pendingImport.toString());
    }

    @Override
    public void onDestroyView() {
        if (previewDialog != null) {
            previewDialog.dismiss();
            previewDialog = null;
        }
        if (pluginList != null) pluginList.setAdapter(null);
        root = null;
        pluginScroll = null;
        modernSegmentedTabs = null;
        btnRefresh = null;
        pluginWebsiteSection = null;
        btnPluginWebsite = null;
        pluginLinkSection = null;
        linkInput = null;
        linkHint = null;
        btnPluginPaste = null;
        btnPluginInstall = null;
        btnImport = null;
        btnExport = null;
        btnImportFallback = null;
        pluginBusy = null;
        statusText = null;
        marketHelp = null;
        panelMarketContainer = null;
        installedControls = null;
        btnPluginUpdates = null;
        searchInput = null;
        btnOnlyCustom = null;
        pluginCount = null;
        btnSort = null;
        pluginEmpty = null;
        pluginList = null;
        presenter = null;
        actions = null;
        super.onDestroyView();
    }

    /**
     * 单一渲染入口：集中将 PluginUiState 的状态快照映射到各个 View 上。
     */
    @Override
    public void onRender(PluginUiState state) {
        if (!isAdded() || root == null) return;

        // 1. Tab 切换与区块显隐
        if (panelMarketContainer != null) panelMarketContainer.setVisibility(state.isMarketTab ? View.VISIBLE : View.GONE);
        if (installedControls != null) installedControls.setVisibility(state.isMarketTab ? View.GONE : View.VISIBLE);

        if (modernSegmentedTabs != null) {
            int targetIndex = state.isMarketTab ? 0 : 1;
            if (modernSegmentedTabs.getSelectedIndex() != targetIndex) {
                modernSegmentedTabs.setSelectedIndex(targetIndex, true);
            }
        }

        // 2. 状态条与加载进度
        if (pluginBusy != null) pluginBusy.setVisibility(state.isBusy ? View.VISIBLE : View.GONE);
        if (statusText != null) statusText.setText(state.statusMessage);

        // 3. 按钮可用态 (保持 HTML 原画鲜亮 primary 色)
        if (btnPluginInstall != null) btnPluginInstall.setEnabled(!state.isBusy);
        if (linkHint != null) linkHint.setText(state.linkHintText);

        boolean notBusy = !state.isBusy;
        if (btnImport != null) btnImport.setEnabled(notBusy);
        if (btnImportFallback != null) btnImportFallback.setEnabled(notBusy);
        if (btnExport != null) btnExport.setEnabled(notBusy);
        if (btnRefresh != null) btnRefresh.setEnabled(notBusy);
        if (btnPluginUpdates != null) btnPluginUpdates.setEnabled(notBusy);

        // 4. 排序与统计
        if (btnSort != null) btnSort.setText(state.sortButtonText);
        if (btnOnlyCustom != null) {
            boolean isCustomOnly = presenter != null && presenter.isHideBuiltin();
            btnOnlyCustom.setText(isCustomOnly ? "显示全部" : "只看自己装的");
            btnOnlyCustom.setTextColor(requireContext().getColor(isCustomOnly ? R.color.primary : R.color.text_secondary));
        }
        if (pluginCount != null) pluginCount.setText(state.countText);

        // 5. 空状态提示
        if (pluginEmpty != null) {
            pluginEmpty.setVisibility(state.isEmptyVisible ? View.VISIBLE : View.GONE);
            pluginEmpty.setText(state.emptyText);
        }

        // 6. 列表数据刷新 (智能比对，坚决杜绝无谓的整表暴力重绘掐死动画)
        boolean itemsChanged = isItemsDifferent(visibleItems, state.displayItems);
        if (itemsChanged) {
            visibleItems.clear();
            visibleItems.addAll(state.displayItems);
            adapter.notifyDataSetChanged();
        }

        // 7. 安装确认弹窗检测（解析完成时立即弹出）
        checkShowInstallPreview();
    }

    private boolean isItemsDifferent(List<PluginRepository.Item> oldList, List<PluginRepository.Item> newList) {
        if (oldList.size() != newList.size()) return true;
        for (int i = 0; i < oldList.size(); i++) {
            PluginRepository.Item a = oldList.get(i);
            PluginRepository.Item b = newList.get(i);
            if (!a.name.equals(b.name) || a.enabled != b.enabled || a.available != b.available
                    || a.updateAvailable != b.updateAvailable || !a.version.equals(b.version)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onLaunchImport(Intent intent) {
        importPicker.launch(intent);
    }

    @Override
    public void onLaunchExport(String fileName, ArrayList<String> selectedPlugins) {
        this.pendingExports = selectedPlugins;
        try {
            exportPicker.launch(fileName);
        } catch (Exception error) {
            pendingExports.clear();
            Toast.makeText(requireContext(), "无法打开保存位置选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onShowInstallPreview(PluginRepository.Preview preview) {
        if (preview != null && repository != null && !repository.isBusy()) {
            showInstallPreview(preview);
        } else {
            checkShowInstallPreview();
        }
    }

    private void checkShowInstallPreview() {
        if (!isAdded() || root == null || previewDialog != null || repository == null) return;
        PluginRepository.Preview preview = repository.preview().getValue();
        if (preview == null || repository.isBusy()) return;
        showInstallPreview(preview);
    }

    private void showInstallPreview(PluginRepository.Preview preview) {
        if (!isAdded() || root == null || previewDialog != null || preview == null) return;
        previewDialog = new AlertDialog.Builder(requireContext())
                .setTitle("确认安装插件")
                .setMessage(preview.description)
                .setNegativeButton("取消", (d, w) -> repository.discardPreview())
                .setPositiveButton("确认安装", (d, w) -> repository.confirmPreview())
                .setOnCancelListener(d -> repository.discardPreview())
                .create();
        previewDialog.setOnDismissListener(d -> previewDialog = null);
        previewDialog.show();
    }

    @Override
    public void onScrollToTop() {
        if (root == null) return;
        if (linkInput != null) linkInput.clearFocus();
        if (searchInput != null) searchInput.clearFocus();
        InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
        if (pluginScroll != null) {
            pluginScroll.post(() -> pluginScroll.scrollTo(0, 0));
        }
    }

    @Override
    public void onSetLinkInputText(String text) {
        if (linkInput != null) linkInput.setText(text);
    }

    private void copyText(String label, String text) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text));
                Toast.makeText(requireContext(), label + " 已复制", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Toast.makeText(requireContext(), "复制失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        private final java.util.Set<String> expandedPlugins = new java.util.HashSet<>();

        class Holder extends RecyclerView.ViewHolder {
            final TextView name, meta, description;
            final ModernBadgeView statusBadge;
            final DshaToggle toggle;
            final Button actionBtn;
            final View detailsPanel;
            final TextView detailText;
            final Button btnAddr, btnRename, btnExport, btnUninstall;

            Holder(View view) {
                super(view);
                name = view.findViewById(R.id.pluginName);
                statusBadge = view.findViewById(R.id.pluginStatusBadge);
                meta = view.findViewById(R.id.pluginStatus);
                description = view.findViewById(R.id.pluginDesc);
                toggle = view.findViewById(R.id.pluginToggle);
                actionBtn = view.findViewById(R.id.pluginActions);
                detailsPanel = view.findViewById(R.id.pluginDetailsPanel);
                detailText = view.findViewById(R.id.pluginDetailText);
                btnAddr = view.findViewById(R.id.btnPluginAddr);
                btnRename = view.findViewById(R.id.btnPluginRename);
                btnExport = view.findViewById(R.id.btnPluginExport);
                btnUninstall = view.findViewById(R.id.btnPluginUninstall);
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_plugin, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            PluginRepository.Item item = visibleItems.get(position);
            holder.name.setText(item.name);

            // 1. 胶囊状态徽章 (.badge: 纯 Skia 自绘 ModernBadgeView)
            boolean isEnabled = item.enabled;
            if (holder.statusBadge != null) {
                holder.statusBadge.setBadge(isEnabled, isEnabled ? "已启用" : (item.available ? "已禁用" : "缺失"));
            }

            // 2. 元信息
            String typeLabel = item.builtin ? "DSHA 内置插件" : item.official ? "官方核心" : "第三方插件";
            String versionStr = item.version.isEmpty() ? "" : item.version + " · ";
            String updateStr = item.updateAvailable ? " · 可更新：" + item.latestVersion : "";
            holder.meta.setText(versionStr + typeLabel + updateStr);

            // 3. 详细描述
            holder.description.setText(item.description.isEmpty()
                    ? (item.official ? "官方核心" : item.builtin ? "DSHA 内置插件" : "第三方插件") : item.description);

            // 4. 自绘胶囊开关 (DshaToggle - 保护平滑位移动画不被列表刷新打断)
            if (holder.toggle != null) {
                if (holder.toggle.isChecked() != item.enabled) {
                    holder.toggle.setChecked(item.enabled, false);
                }
                holder.toggle.setEnabled(item.available || item.enabled);
                holder.toggle.setOnCheckedChangeListener((t, checked) -> {
                    if (actions != null && checked != item.enabled) {
                        if (holder.statusBadge != null) {
                            holder.statusBadge.setBadge(checked, checked ? "已启用" : "已禁用");
                        }
                        actions.onToggleItem(item, checked);
                    }
                });
            }

            // 5. 更多 ▸ 展开折叠内嵌抽屉
            boolean isExpanded = expandedPlugins.contains(item.name);
            if (holder.detailsPanel != null) {
                holder.detailsPanel.setVisibility(isExpanded ? View.VISIBLE : View.GONE);
            }
            if (holder.actionBtn != null) {
                holder.actionBtn.setText(isExpanded ? "收起 ▴" : "更多 ▸");
                holder.actionBtn.setOnClickListener(v -> {
                    int pos = holder.getAdapterPosition();
                    if (pos == RecyclerView.NO_POSITION) return;
                    if (expandedPlugins.contains(item.name)) {
                        expandedPlugins.remove(item.name);
                    } else {
                        expandedPlugins.add(item.name);
                    }
                    notifyItemChanged(pos);
                });
            }

            // 6. 展开抽屉内的详情与 4 个小按钮 (移除开发者与权限，仅保留版本与配置路径)
            String configPath = item.builtin
                    ? "/data/adb/dsha/rootfs/root/dsha-" + item.name + "/"
                    : "/data/adb/dsha/rootfs/root/.dsh/plugin-src/" + item.name + "/";
            if (holder.detailText != null) {
                holder.detailText.setText("版本：" + (item.version.isEmpty() ? "未知" : item.version)
                        + (item.updateAvailable ? "（可更新 " + item.latestVersion + "）" : "")
                        + "\n配置路径：" + configPath);
            }

            if (holder.btnAddr != null) {
                holder.btnAddr.setOnClickListener(v -> copyText("配置路径", configPath));
            }
            if (holder.btnRename != null) {
                holder.btnRename.setOnClickListener(v -> copyText("插件名称", item.name));
            }
            if (holder.btnExport != null) {
                holder.btnExport.setOnClickListener(v -> {
                    ArrayList<String> list = new ArrayList<>();
                    list.add(item.name);
                    onLaunchExport(item.name + ".tar.gz", list);
                });
            }
            if (holder.btnUninstall != null) {
                holder.btnUninstall.setOnClickListener(v -> {
                    if (item.builtin) {
                        Toast.makeText(requireContext(), "DSHA 系统内置核心插件无法卸载", Toast.LENGTH_SHORT).show();
                    } else if (actions != null) {
                        actions.onItemActionClick(item);
                    }
                });
            }
        }

        @Override
        public int getItemCount() {
            return visibleItems.size();
        }
    }
}
