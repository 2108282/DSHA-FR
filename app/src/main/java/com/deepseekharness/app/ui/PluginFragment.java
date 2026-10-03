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
    private TextView btnMarketTab;
    private TextView btnInstalledTab;
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
    private LinearLayout installedControls;
    private Button btnPluginUpdates;
    private EditText searchInput;
    private CheckBox chkHideBuiltin;
    private TextView pluginCount;
    private TextView btnSort;
    private TextView pluginEmpty;
    private RecyclerView pluginList;

    private final List<PluginRepository.Item> visibleItems = new ArrayList<>();
    private final Adapter adapter = new Adapter();

    private ArrayList<String> pendingExports = new ArrayList<>();
    private Uri pendingImport;
    private AlertDialog previewDialog;

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

    private final ActivityResultLauncher<String> readPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), allowed -> {
                Uri selected = pendingImport;
                pendingImport = null;
                if (allowed && selected != null) repository.importArchive(selected);
                else repository.selectionMessage("未获得文件读取权限，请改用系统文件选择器导入。");
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
        btnMarketTab = view.findViewById(R.id.btnMarket);
        btnInstalledTab = view.findViewById(R.id.btnInstalled);
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
        pluginBusy = view.findViewById(R.id.pluginBusy);
        statusText = view.findViewById(R.id.statusText);
        marketHelp = view.findViewById(R.id.marketHelp);
        installedControls = view.findViewById(R.id.installedControls);
        btnPluginUpdates = view.findViewById(R.id.btnPluginUpdates);
        searchInput = view.findViewById(R.id.pluginSearch);
        chkHideBuiltin = view.findViewById(R.id.chkHideBuiltin);
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
        btnMarketTab.setOnClickListener(v -> actions.onSelectTab(true));
        btnInstalledTab.setOnClickListener(v -> actions.onSelectTab(false));
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

        chkHideBuiltin.setOnCheckedChangeListener((v, checked) -> actions.onHideBuiltinChanged(checked));

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
        repository.state().observe(getViewLifecycleOwner(), state -> presenter.updateRepoState(state));
        repository.preview().observe(getViewLifecycleOwner(), preview -> {
            if (preview != null) showInstallPreview(preview);
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
        btnMarketTab = null;
        btnInstalledTab = null;
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
        installedControls = null;
        btnPluginUpdates = null;
        searchInput = null;
        chkHideBuiltin = null;
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
        if (marketHelp != null) marketHelp.setVisibility(state.isMarketTab ? View.VISIBLE : View.GONE);
        if (pluginWebsiteSection != null) pluginWebsiteSection.setVisibility(state.isMarketTab ? View.VISIBLE : View.GONE);
        if (pluginLinkSection != null) pluginLinkSection.setVisibility(state.isMarketTab ? View.VISIBLE : View.GONE);
        if (installedControls != null) installedControls.setVisibility(state.isMarketTab ? View.GONE : View.VISIBLE);
        if (pluginList != null) pluginList.setVisibility(state.isMarketTab ? View.GONE : View.VISIBLE);

        if (btnMarketTab != null) {
            btnMarketTab.setBackgroundResource(state.isMarketTab ? R.drawable.bg_tab_on : R.drawable.bg_tab);
            btnMarketTab.setTextColor(requireContext().getColor(state.isMarketTab ? R.color.primary : R.color.text_secondary));
        }
        if (btnInstalledTab != null) {
            btnInstalledTab.setBackgroundResource(state.isMarketTab ? R.drawable.bg_tab : R.drawable.bg_tab_on);
            btnInstalledTab.setTextColor(requireContext().getColor(state.isMarketTab ? R.color.text_secondary : R.color.primary));
        }

        // 2. 状态条与加载进度
        if (pluginBusy != null) pluginBusy.setVisibility(state.isBusy ? View.VISIBLE : View.GONE);
        if (statusText != null) statusText.setText(state.statusMessage);

        // 3. 按钮可用态
        if (btnPluginInstall != null) btnPluginInstall.setEnabled(state.isInstallButtonEnabled);
        if (linkHint != null) linkHint.setText(state.linkHintText);

        boolean notBusy = !state.isBusy;
        if (btnImport != null) btnImport.setEnabled(notBusy);
        if (btnImportFallback != null) btnImportFallback.setEnabled(notBusy);
        if (btnExport != null) btnExport.setEnabled(notBusy);
        if (btnRefresh != null) btnRefresh.setEnabled(notBusy);
        if (btnPluginUpdates != null) btnPluginUpdates.setEnabled(notBusy);

        // 4. 排序与统计
        if (btnSort != null) btnSort.setText(state.sortButtonText);
        if (pluginCount != null) pluginCount.setText(state.countText);

        // 5. 空状态提示
        if (pluginEmpty != null) {
            pluginEmpty.setVisibility(state.isEmptyVisible ? View.VISIBLE : View.GONE);
            pluginEmpty.setText(state.emptyText);
        }

        // 6. 列表数据刷新
        visibleItems.clear();
        visibleItems.addAll(state.displayItems);
        adapter.notifyDataSetChanged();
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
        showInstallPreview(preview);
    }

    private void showInstallPreview(PluginRepository.Preview preview) {
        if (!isAdded() || root == null || repository.isBusy() || previewDialog != null || preview == null) return;
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

    private class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        class Holder extends RecyclerView.ViewHolder {
            final TextView name, state, description;
            final Switch toggle;
            final View actionBtn;
            Holder(View view) {
                super(view);
                name = view.findViewById(R.id.pluginName);
                state = view.findViewById(R.id.pluginStatus);
                description = view.findViewById(R.id.pluginDesc);
                toggle = view.findViewById(R.id.pluginSwitch);
                actionBtn = view.findViewById(R.id.pluginActions);
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
            holder.state.setText((item.available ? (item.enabled ? "已启用" : "已禁用") : "实体缺失，请重新导入")
                    + (item.version.isEmpty() ? "" : " · " + item.version)
                    + (item.updateAvailable ? "\n可更新：" + item.latestVersion
                            : (item.latestVersion.isEmpty() ? "" : "\n上次检查版本：" + item.latestVersion)
                            + (item.updateMessage.isEmpty() ? "" : "\n" + item.updateMessage))
                    + (item.rollbackVersion.isEmpty() ? "" : "\n可回退：" + item.rollbackVersion));
            holder.state.setTextColor(requireContext().getColor(
                    !item.available ? R.color.warn : item.enabled ? R.color.primary : R.color.text_muted));
            holder.description.setText(item.description.isEmpty()
                    ? (item.official ? "官方核心" : item.builtin ? "DSHA 内置插件" : "第三方插件") : item.description);

            if (holder.actionBtn != null) {
                holder.actionBtn.setOnClickListener(v -> {
                    if (actions != null) actions.onItemActionClick(item);
                });
                holder.actionBtn.setContentDescription("更多操作：" + item.name);
            }

            holder.toggle.setVisibility(View.VISIBLE);
            holder.toggle.setOnCheckedChangeListener(null);
            holder.toggle.setChecked(item.enabled);
            holder.toggle.setContentDescription((item.enabled ? "禁用 " : "启用 ") + item.name);
            holder.toggle.jumpDrawablesToCurrentState();
            holder.toggle.setEnabled(!repository.isBusy() && (item.available || item.enabled));
            holder.toggle.setOnCheckedChangeListener((v, checked) -> {
                if (actions != null && checked != item.enabled) {
                    actions.onToggleItem(item, checked);
                }
            });
            holder.itemView.setOnLongClickListener(v -> {
                if (actions != null) actions.onItemActionClick(item);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return visibleItems.size();
        }
    }
}
