package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.DshModelRepository;
import com.deepseekharness.app.util.ModelConfiguration;
import com.deepseekharness.app.util.ToastHelper;
import com.deepseekharness.app.util.UiText;
import com.google.gson.*;
import java.util.*;

/**
 * 模型管理页面 (1:1 原生 Skia 现代卡片设计):
 * - 主页面: 对齐设置页与详细配置页风格，ModernCardView 双层微阴影卡片、极光漫射背景、统一顶栏日夜间切换
 * - 下级页面: 模型详情、高级 JSON 配置、服务商模板选择、可用模型发现均重绘为现代 Skia 卡片弹窗
 * - 业务逻辑: 100% 完整保留 DSH 模型服务商读写、校验、密钥管理与状态同步
 */
public final class ModelSetupActivity extends AppCompatActivity {

    private DshModelRepository repository;
    private Draft draft;

    // 页面与顶栏
    private TextView appBarTitle;
    private View btnTheme;
    private View modelBack;
    private TextView tvMainTitle;
    private TextView tvMainDesc;
    private View statusBanner;
    private TextView tvStatusText;

    // 目录视图 (Directory Mode)
    private View layoutDirectoryContainer;
    private LinearLayout layoutConfiguredProvidersList;
    private TextView tvProvidersEmpty;
    private View rowAddCustom;
    private View rowChooseBuiltin;
    private Button btnReloadConfig;
    private Button btnContinueToDsha;

    // 编辑视图 (Editor Mode)
    private View layoutEditorContainer;
    private View containerRoute;
    private EditText editRoute;
    private View containerName;
    private EditText editName;
    private EditText editEndpoint;
    private DshaSelectView selectProtocol;
    private EditText editKey;

    private View containerHeadersSection;
    private LinearLayout layoutHeadersList;
    private Button btnAddHeader;
    private final List<EditText[]> headerFields = new ArrayList<>();

    private LinearLayout layoutModelsList;
    private Button btnFetchModels;
    private Button btnAddModel;

    private View rowAdvanced;
    private View dividerDeleteProvider;
    private View rowDeleteProvider;
    private Button btnSaveProvider;

    private List<String> protocols = List.of();
    private JsonObject current;
    private long observedSave;

    public static final class Draft extends ViewModel {
        JsonObject entry, original, value, extra;
        JsonArray modelList, headers;
        long revision, generation, handledDiscovery;
        boolean custom;
        String route = "", name = "", endpoint = "", protocol = "", key = "";

        boolean shouldShowDiscovery(long serial) {
            return entry != null && serial > handledDiscovery;
        }

        void consumeDiscovery(long serial) {
            handledDiscovery = Math.max(handledDiscovery, serial);
        }

        void clear() {
            entry = null;
            original = null;
            value = null;
            extra = null;
            modelList = null;
            headers = null;
            key = "";
            route = "";
            name = "";
            endpoint = "";
            protocol = "";
        }

        @Override
        protected void onCleared() {
            key = "";
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeController.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_model_setup);

        // 1. 初始化极光漫射 (Android 12+ 硬件加速模糊)
        View auroraView = findViewById(R.id.global_aurora);
        if (auroraView != null && Build.VERSION.SDK_INT >= 31) {
            float blurPx = 80f * getResources().getDisplayMetrics().density;
            try {
                auroraView.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                        blurPx, blurPx, android.graphics.Shader.TileMode.CLAMP));
            } catch (Throwable ignored) { }
        }

        // 2. 绑定顶栏与公用组件
        appBarTitle = findViewById(R.id.app_bar_title);
        btnTheme = findViewById(R.id.btn_theme);
        ImageView themeIcon = findViewById(R.id.img_theme_icon);
        if (btnTheme != null && themeIcon != null) {
            boolean dark = ThemeController.isDark(this);
            themeIcon.setImageResource(dark ? R.drawable.ic_moon : R.drawable.ic_sun);
            themeIcon.setContentDescription(dark ? "夜间模式" : "日间模式");
            btnTheme.setOnClickListener(v -> ThemeController.toggle(this));
        }

        modelBack = findViewById(R.id.model_back);
        if (modelBack != null) {
            modelBack.setOnClickListener(v -> leave());
        }

        tvMainTitle = findViewById(R.id.tv_main_title);
        tvMainDesc = findViewById(R.id.tv_main_desc);
        statusBanner = findViewById(R.id.layout_status_banner);
        tvStatusText = findViewById(R.id.tv_status_text);

        // 3. 绑定目录视图组件
        layoutDirectoryContainer = findViewById(R.id.layout_directory_container);
        layoutConfiguredProvidersList = findViewById(R.id.layout_configured_providers_list);
        tvProvidersEmpty = findViewById(R.id.tv_providers_empty);
        rowAddCustom = findViewById(R.id.row_add_custom);
        rowChooseBuiltin = findViewById(R.id.row_choose_builtin);
        btnReloadConfig = findViewById(R.id.btn_reload_config);
        btnContinueToDsha = findViewById(R.id.btn_continue_to_dsha);

        if (rowAddCustom != null) rowAddCustom.setOnClickListener(v -> openCustom());
        if (rowChooseBuiltin != null) rowChooseBuiltin.setOnClickListener(v -> chooseProvider());
        if (btnReloadConfig != null) btnReloadConfig.setOnClickListener(v -> {
            if (repository != null) {
                ToastHelper.makeText(this, t("正在重新读取配置…", "Reloading settings..."), Toast.LENGTH_SHORT).show();
                repository.load();
            }
        });
        if (btnContinueToDsha != null) btnContinueToDsha.setOnClickListener(v -> leave());

        // 4. 绑定编辑视图组件
        layoutEditorContainer = findViewById(R.id.layout_editor_container);
        containerRoute = findViewById(R.id.container_route);
        editRoute = findViewById(R.id.edit_route);
        containerName = findViewById(R.id.container_name);
        editName = findViewById(R.id.edit_name);
        editEndpoint = findViewById(R.id.edit_endpoint);
        selectProtocol = findViewById(R.id.select_protocol);
        editKey = findViewById(R.id.edit_key);

        containerHeadersSection = findViewById(R.id.container_headers_section);
        layoutHeadersList = findViewById(R.id.layout_headers_list);
        btnAddHeader = findViewById(R.id.btn_add_header);
        if (btnAddHeader != null) {
            btnAddHeader.setOnClickListener(v -> {
                captureHeaders();
                if (draft.headers != null && draft.headers.size() < 32) {
                    draft.headers.add(new JsonObject());
                    renderHeaders();
                }
            });
        }

        layoutModelsList = findViewById(R.id.layout_models_list);
        btnFetchModels = findViewById(R.id.btn_fetch_models);
        btnAddModel = findViewById(R.id.btn_add_model);
        if (btnFetchModels != null) btnFetchModels.setOnClickListener(v -> fetchModels());
        if (btnAddModel != null) btnAddModel.setOnClickListener(v -> editModel(-1));

        rowAdvanced = findViewById(R.id.row_advanced);
        if (rowAdvanced != null) rowAdvanced.setOnClickListener(v -> advanced());

        dividerDeleteProvider = findViewById(R.id.divider_delete_provider);
        rowDeleteProvider = findViewById(R.id.row_delete_provider);
        if (rowDeleteProvider != null) rowDeleteProvider.setOnClickListener(v -> confirmDeleteProvider());

        btnSaveProvider = findViewById(R.id.btn_save_provider);
        if (btnSaveProvider != null) btnSaveProvider.setOnClickListener(v -> save());

        // 5. 初始化 ViewModel 与状态观测
        repository = new ViewModelProvider(this).get(DshModelRepository.class);
        draft = new ViewModelProvider(this).get(Draft.class);
        observedSave = repository.savedRevision.getValue() == null ? 0 : repository.savedRevision.getValue();
        current = repository.data.getValue();

        repository.message.observe(this, msg -> {
            updateStatusBanner(msg);
            if (msg != null && !msg.isEmpty()
                    && !msg.equals(t("模型设置已同步", "Model settings synced"))
                    && !msg.equals(t("正在同步…", "Syncing…"))) {
                ToastHelper.makeText(this, msg, Toast.LENGTH_SHORT).show();
                if (msg.contains("无法") || msg.contains("失败") || msg.contains("拒绝")
                        || msg.contains("answered") || msg.contains("401") || msg.contains("403")
                        || msg.contains("Error") || msg.contains("error")) {
                    new DshaDialogBuilder(this)
                            .setTitle(t("提示", "Notice"))
                            .setMessage(msg)
                            .setPositiveButton(t("知道了", "OK"), null)
                            .show();
                }
            }
        });

        repository.busy.observe(this, busy -> {
            if (btnSaveProvider != null) btnSaveProvider.setEnabled(!busy);
            if (btnFetchModels != null) btnFetchModels.setEnabled(!busy);
        });

        repository.data.observe(this, data -> {
            current = data;
            if (draft.entry == null) {
                showDirectory();
            }
        });

        repository.modelDiscovery.observe(this, result -> {
            if (result != null && draft.shouldShowDiscovery(result.serial)) {
                showDiscoveredModels(result);
            }
        });

        repository.savedRevision.observe(this, revision -> {
            if (revision > observedSave) {
                observedSave = revision;
                draft.clear();
                ToastHelper.makeText(this, t("已成功保存并同步到 Web UI！", "Saved and synced to Web UI!"), Toast.LENGTH_SHORT).show();
                showDirectory();
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                leave();
            }
        });

        // 首次启动或还原展示
        if (draft.entry != null && current != null) {
            showEditor();
        } else if (current != null) {
            showDirectory();
        } else {
            updateStatusBanner(t("正在连接服务商配置服务…", "Connecting to configuration service..."));
            repository.load();
        }
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.fragment_pop_enter, R.anim.fragment_pop_exit);
    }

    private void updateStatusBanner(String msg) {
        if (statusBanner == null || tvStatusText == null) return;
        if (msg == null || msg.trim().isEmpty()) {
            statusBanner.setVisibility(View.GONE);
        } else {
            statusBanner.setVisibility(View.VISIBLE);
            tvStatusText.setText(msg);
        }
    }

    private void leave() {
        if (Boolean.TRUE.equals(repository.busy.getValue()) && draft.entry != null) {
            updateStatusBanner(t("正在处理，请等待结果。", "Working. Please wait for the result."));
            return;
        }
        if (draft.entry != null) {
            capture();
            View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_discard_edits, null);
            AlertDialog dialog = new DshaDialogBuilder(this).setView(dialogView).create();
            Button btnCancel = dialogView.findViewById(R.id.btnDiscardCancel);
            Button btnConfirm = dialogView.findViewById(R.id.btnDiscardConfirm);
            if (btnCancel != null) btnCancel.setOnClickListener(v -> dialog.dismiss());
            if (btnConfirm != null) {
                btnConfirm.setOnClickListener(v -> {
                    dialog.dismiss();
                    draft.clear();
                    showDirectory();
                });
            }
            dialog.show();
            return;
        }
        finish();
    }

    private JsonObject namespace(String name) {
        if (current != null && current.has("settings") && current.getAsJsonObject("settings").has("namespaces")) {
            for (JsonElement item : current.getAsJsonObject("settings").getAsJsonArray("namespaces")) {
                if (name.equals(s(item.getAsJsonObject(), "ns"))) {
                    return item.getAsJsonObject();
                }
            }
        }
        return new JsonObject();
    }

    // =========================================================================
    // 目录视图 (Directory Mode) 逻辑
    // =========================================================================

    private void showDirectory() {
        if (current == null) return;

        layoutEditorContainer.setVisibility(View.GONE);
        layoutDirectoryContainer.setVisibility(View.VISIBLE);

        tvMainTitle.setText(t("模型配置与服务商", "Model Settings & Providers"));
        tvMainDesc.setText(t("服务商、连接方式与模型目录。保存后与 Web UI 同步。",
                "Providers, connections and models. Saved settings sync with Web UI."));

        boolean writable = current.has("settings") && current.getAsJsonObject("settings").has("writable")
                && current.getAsJsonObject("settings").get("writable").getAsBoolean();

        layoutConfiguredProvidersList.removeAllViews();
        int providerCount = 0;

        if (current.has("providers") && current.get("providers").isJsonArray()) {
            for (JsonElement item : current.getAsJsonArray("providers")) {
                JsonObject entry = item.getAsJsonObject();
                JsonObject ns = namespace(s(entry, "settingsNs"));
                JsonArray path = entry.has("settingsPath") ? entry.getAsJsonArray("settingsPath") : new JsonArray();
                JsonElement value = ModelConfiguration.at(ns.get("value"), path);
                if (path.size() > 0 && (value == null || value.isJsonNull())) continue;

                String title = s(entry, "displayName");
                if (title.isEmpty()) title = s(entry, "provider");
                final String finalTitle = title;

                JsonObject profile = ModelConfiguration.object(value);
                String detail = s(profile, "baseURL");
                if (detail.isEmpty()) {
                    detail = t("使用服务商默认地址", "Uses provider default endpoint");
                }

                providerCount++;
                addConfiguredProviderRow(entry, finalTitle, detail, writable);
            }
        }

        tvProvidersEmpty.setVisibility(providerCount == 0 ? View.VISIBLE : View.GONE);

        if (getIntent().getBooleanExtra("first_run", false)) {
            btnContinueToDsha.setVisibility(View.VISIBLE);
        } else {
            btnContinueToDsha.setVisibility(View.GONE);
        }
    }

    private void addConfiguredProviderRow(JsonObject entry, String title, String detail, boolean writable) {
        if (layoutConfiguredProvidersList.getChildCount() > 0) {
            View divider = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            lp.leftMargin = dp(66);
            divider.setLayoutParams(lp);
            divider.setBackgroundColor(getColor(R.color.line_soft));
            layoutConfiguredProvidersList.addView(divider);
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        android.util.TypedValue selVal = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, selVal, true);
        row.setBackgroundResource(selVal.resourceId);
        row.setClickable(true);
        row.setFocusable(true);

        // 图标盒
        FrameLayout iconBox = new FrameLayout(this);
        iconBox.setBackgroundResource(R.drawable.bg_settings_icon_box);
        LinearLayout.LayoutParams ibLp = new LinearLayout.LayoutParams(dp(38), dp(38));
        ImageView iconView = new ImageView(this);
        iconView.setImageResource(R.drawable.ic_ui_link);
        int targetPrimary = MonetEngine.resolveCurrentPalette(this).primaryColor;
        iconView.setImageTintList(android.content.res.ColorStateList.valueOf(targetPrimary));
        FrameLayout.LayoutParams ivLp = new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER);
        iconBox.addView(iconView, ivLp);
        row.addView(iconBox, ibLp);

        // 文字信息
        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        tcLp.leftMargin = dp(12);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextColor(getColor(R.color.text));
        tvTitle.setTextSize(15);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        textCol.addView(tvTitle);

        TextView tvDetail = new TextView(this);
        tvDetail.setText(detail);
        tvDetail.setTextColor(getColor(R.color.text_muted));
        tvDetail.setTextSize(12);
        tvDetail.setSingleLine(true);
        tvDetail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams dtLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dtLp.topMargin = dp(1);
        textCol.addView(tvDetail, dtLp);

        row.addView(textCol, tcLp);

        // 右侧操作
        if (writable && !"llm-deepseek".equals(s(entry, "settingsNs"))) {
            TextView btnDelete = new TextView(this);
            btnDelete.setText(t("删除", "Delete"));
            btnDelete.setTextColor(getColor(R.color.err));
            btnDelete.setTextSize(12);
            btnDelete.setTypeface(null, android.graphics.Typeface.BOLD);
            btnDelete.setGravity(Gravity.CENTER);
            btnDelete.setBackgroundResource(R.drawable.bg_btn_danger_small);
            btnDelete.setPadding(dp(10), dp(4), dp(10), dp(4));
            LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28));
            delLp.rightMargin = dp(8);
            btnDelete.setOnClickListener(v -> confirmDeleteDirectoryEntry(entry, title));
            row.addView(btnDelete, delLp);
        }

        ImageView chevron = new ImageView(this);
        chevron.setImageResource(R.drawable.ic_chevron_right);
        chevron.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.text_muted)));
        row.addView(chevron, new LinearLayout.LayoutParams(dp(18), dp(18)));

        row.setOnClickListener(v -> {
            if (writable) open(entry, false);
        });

        layoutConfiguredProvidersList.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void confirmDeleteDirectoryEntry(JsonObject entry, String title) {
        JsonObject ns = namespace(s(entry, "settingsNs"));
        if (!ns.has("revision")) return;
        long revision = ns.get("revision").getAsLong();
        long gen = current.has("generation") ? current.get("generation").getAsLong() : 0;
        JsonArray path = entry.has("settingsPath") ? entry.getAsJsonArray("settingsPath") : new JsonArray();
        JsonElement val = ModelConfiguration.at(ns.get("value"), path);
        String ref = "";
        if (val != null && val.isJsonObject()) {
            ref = s(val.getAsJsonObject(), "apiKeyEnv");
        }
        final String finalRef = ref;
        new DshaDialogBuilder(this)
                .setTitle(t("删除服务商？", "Delete provider?"))
                .setMessage(t("确定要删除「" + title + "」的配置吗？这会从 DSH 中移除该提供方与关联密钥。",
                        "Delete " + title + "? This removes the configuration and stored API key."))
                .setNegativeButton(t("取消", "Cancel"), null)
                .setPositiveButton(t("删除", "Delete"), (d, w) -> {
                    repository.deleteProvider(s(entry, "settingsNs"), path, revision, gen, finalRef);
                })
                .show();
    }

    private void chooseProvider() {
        if (current == null || !current.has("providers")) return;

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_model_select_provider, null);
        AlertDialog dialog = new DshaDialogBuilder(this).setView(dialogView).create();

        LinearLayout listContainer = dialogView.findViewById(R.id.layout_dialog_provider_list);
        listContainer.removeAllViews();

        // 选项 1: 自定义提供方
        addProviderChoiceItem(listContainer, t("自定义提供方", "Custom provider"), "OpenAI / Anthropic / 自建 API", R.drawable.ic_ui_link, () -> {
            dialog.dismiss();
            openCustom();
        });

        // 内置服务商模板
        for (JsonElement item : current.getAsJsonArray("providers")) {
            JsonObject prov = item.getAsJsonObject();
            String name = s(prov, "displayName");
            if (name.isEmpty()) name = s(prov, "provider");
            final String finalName = name;
            String desc = s(prov, "provider");
            addProviderChoiceItem(listContainer, finalName, desc, R.drawable.ic_ui2_box, () -> {
                dialog.dismiss();
                open(prov, false);
            });
        }

        Button btnCancel = dialogView.findViewById(R.id.btn_dialog_provider_cancel);
        btnCancel.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private void addProviderChoiceItem(LinearLayout parent, String title, String subtitle, int iconRes, Runnable onClick) {
        if (parent.getChildCount() > 0) {
            View divider = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            lp.leftMargin = dp(62);
            divider.setLayoutParams(lp);
            divider.setBackgroundColor(getColor(R.color.line_soft));
            parent.addView(divider);
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(13), dp(14), dp(13));
        android.util.TypedValue choiceVal = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, choiceVal, true);
        row.setBackgroundResource(choiceVal.resourceId);
        row.setClickable(true);
        row.setFocusable(true);

        FrameLayout iconBox = new FrameLayout(this);
        iconBox.setBackgroundResource(R.drawable.bg_settings_icon_box);
        LinearLayout.LayoutParams ibLp = new LinearLayout.LayoutParams(dp(36), dp(36));
        ImageView iconView = new ImageView(this);
        iconView.setImageResource(iconRes);
        iconView.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.primary)));
        iconBox.addView(iconView, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        row.addView(iconBox, ibLp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        tcLp.leftMargin = dp(12);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextColor(getColor(R.color.text));
        tvTitle.setTextSize(14);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        textCol.addView(tvTitle);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView tvSub = new TextView(this);
            tvSub.setText(subtitle);
            tvSub.setTextColor(getColor(R.color.text_muted));
            tvSub.setTextSize(11);
            textCol.addView(tvSub);
        }

        row.addView(textCol, tcLp);

        ImageView chevron = new ImageView(this);
        chevron.setImageResource(R.drawable.ic_chevron_right);
        chevron.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.text_muted)));
        row.addView(chevron, new LinearLayout.LayoutParams(dp(16), dp(16)));

        row.setOnClickListener(v -> onClick.run());
        parent.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void openCustom() {
        JsonObject entry = new JsonObject();
        entry.addProperty("provider", "");
        entry.addProperty("settingsNs", "llm-pi-ai");
        entry.add("settingsPath", ModelConfiguration.path("providers", ""));
        open(entry, true);
        if (draft.entry != null && draft.custom) {
            draft.protocol = "openai-completions";
            showEditor();
        }
    }

    private void open(JsonObject entry, boolean custom) {
        JsonObject ns = namespace(s(entry, "settingsNs"));
        if (!ns.has("revision")) {
            updateStatusBanner(t("此服务商组件尚不可用，请检查插件。", "Provider component unavailable. Check plugins."));
            return;
        }
        draft.entry = entry.deepCopy();
        draft.custom = custom;
        draft.revision = ns.get("revision").getAsLong();
        draft.generation = current.has("generation") ? current.get("generation").getAsLong() : 0;
        draft.extra = null;
        JsonArray path = entry.has("settingsPath") ? entry.getAsJsonArray("settingsPath") : new JsonArray();
        draft.original = ModelConfiguration.object(ModelConfiguration.at(ns.get("user"), path)).deepCopy();
        draft.value = ModelConfiguration.object(ModelConfiguration.at(ns.get("value"), path)).deepCopy();
        draft.route = s(entry, "provider");
        draft.name = s(draft.value, "displayName");
        draft.endpoint = s(draft.value, "baseURL");
        draft.protocol = s(draft.value, deepseek() ? "protocol" : "api");
        draft.key = "";
        draft.headers = ModelConfiguration.headerRows(ModelConfiguration.object(draft.value.get("headers")));
        updateStatusBanner(t("编辑完成后，点击下方保存。", "Save below when your edits are ready."));
        draft.modelList = draft.value.has("models")
                ? draft.value.getAsJsonArray("models").deepCopy()
                : new JsonArray();
        showEditor();
    }

    private boolean deepseek() {
        return "llm-deepseek".equals(s(draft.entry, "settingsNs"));
    }

    // =========================================================================
    // 编辑视图 (Editor Mode) 逻辑
    // =========================================================================

    private void showEditor() {
        layoutDirectoryContainer.setVisibility(View.GONE);
        layoutEditorContainer.setVisibility(View.VISIBLE);

        tvMainTitle.setText(draft.custom ? t("自定义提供方", "Custom provider") : s(draft.entry, "displayName"));
        tvMainDesc.setText(t("编辑服务商参数、密钥与可用模型，保存后与 Web UI 同步。",
                "Edit provider settings, API keys, and models. Synced with Web UI."));

        // 1. 基础连接字段
        containerRoute.setVisibility(draft.custom ? View.VISIBLE : View.GONE);
        editRoute.setText(draft.route);

        containerName.setVisibility(deepseek() ? View.GONE : View.VISIBLE);
        editName.setText(draft.name);

        editEndpoint.setText(draft.endpoint);
        editEndpoint.setHint(deepseek()
                ? t("留空使用 DeepSeek 官方地址", "Leave blank for the official DeepSeek endpoint")
                : "https://api.example.com/v1");

        // 协议选项
        JsonArray protocolPath = draft.entry.has("settingsPath") ? draft.entry.getAsJsonArray("settingsPath").deepCopy() : new JsonArray();
        protocolPath.add(deepseek() ? "protocol" : "api");
        protocols = new ArrayList<>();
        protocols.add(t("服务商默认", "Provider default"));
        protocols.addAll(ModelConfiguration.choices(
                namespace(s(draft.entry, "settingsNs")).getAsJsonObject("schema"), protocolPath));
        if (!draft.protocol.isEmpty() && !protocols.contains(draft.protocol)) {
            protocols.add(draft.protocol);
        }
        selectProtocol.setAdapter(new ArrayAdapter<>(this, R.layout.item_data_choice, protocols));
        selectProtocol.setSelection(Math.max(0, protocols.indexOf(draft.protocol)));

        editKey.setText(draft.key);

        // 2. 自定义请求头
        if (deepseek()) {
            containerHeadersSection.setVisibility(View.GONE);
        } else {
            containerHeadersSection.setVisibility(View.VISIBLE);
            renderHeaders();
        }

        // 3. 模型目录
        renderModels();

        // 4. 危险操作 (删除此服务商配置)
        if (!deepseek() && (!draft.custom || isConfiguredRoute(draft.route))) {
            dividerDeleteProvider.setVisibility(View.VISIBLE);
            rowDeleteProvider.setVisibility(View.VISIBLE);
        } else {
            dividerDeleteProvider.setVisibility(View.GONE);
            rowDeleteProvider.setVisibility(View.GONE);
        }

        btnSaveProvider.setEnabled(!Boolean.TRUE.equals(repository.busy.getValue()));
    }

    private void capture() {
        if (draft.entry == null || editEndpoint == null) return;
        if (editRoute != null) draft.route = editRoute.getText().toString().trim().toLowerCase(Locale.ROOT);
        if (editName != null) draft.name = editName.getText().toString().trim();
        draft.endpoint = editEndpoint.getText().toString().trim().replaceAll("\\s+", "");
        draft.protocol = selectProtocol.getSelectedItemPosition() == 0
                ? ""
                : protocols.get(selectProtocol.getSelectedItemPosition());
        draft.key = editKey.getText().toString().trim().replaceAll("[\\r\\n\\t ]", "");
        captureHeaders();
    }

    private void captureHeaders() {
        if (layoutHeadersList == null) return;
        JsonArray rows = new JsonArray();
        for (EditText[] fields : headerFields) {
            JsonObject row = new JsonObject();
            row.addProperty("name", fields[0].getText().toString().trim());
            row.addProperty("value", fields[1].getText().toString().trim());
            rows.add(row);
        }
        draft.headers = rows;
    }

    private void renderHeaders() {
        layoutHeadersList.removeAllViews();
        headerFields.clear();

        if (draft.headers == null) return;

        for (int i = 0; i < draft.headers.size(); i++) {
            final int index = i;
            JsonObject entry = draft.headers.get(i).getAsJsonObject();

            LinearLayout headerRowCard = new LinearLayout(this);
            headerRowCard.setOrientation(LinearLayout.VERTICAL);
            headerRowCard.setBackgroundResource(R.drawable.bg_input);
            headerRowCard.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams hrcLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hrcLp.topMargin = dp(index == 0 ? 0 : 8);

            // 名称输入
            TextView lblName = new TextView(this);
            lblName.setText(t("请求头名称", "Header Name"));
            lblName.setTextColor(getColor(R.color.text_secondary));
            lblName.setTextSize(11);
            lblName.setTypeface(null, android.graphics.Typeface.BOLD);
            headerRowCard.addView(lblName);

            EditText editHeaderName = new EditText(this);
            editHeaderName.setText(s(entry, "name"));
            editHeaderName.setHint("如 x-custom-token");
            editHeaderName.setTextSize(13);
            editHeaderName.setTextColor(getColor(R.color.text));
            editHeaderName.setHintTextColor(getColor(R.color.text_muted));
            editHeaderName.setBackground(null);
            editHeaderName.setPadding(0, dp(4), 0, dp(6));
            editHeaderName.setSingleLine(true);
            headerRowCard.addView(editHeaderName);

            View hdiv = new View(this);
            hdiv.setBackgroundColor(getColor(R.color.line_soft));
            headerRowCard.addView(hdiv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

            // 值输入
            TextView lblVal = new TextView(this);
            lblVal.setText(t("请求头内容", "Header Value"));
            lblVal.setTextColor(getColor(R.color.text_secondary));
            lblVal.setTextSize(11);
            lblVal.setTypeface(null, android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams lblValLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lblValLp.topMargin = dp(6);
            headerRowCard.addView(lblVal, lblValLp);

            EditText editHeaderValue = new EditText(this);
            editHeaderValue.setText(s(entry, "value"));
            editHeaderValue.setHint(t("服务商要求的值", "Value required by provider"));
            editHeaderValue.setTextSize(13);
            editHeaderValue.setTextColor(getColor(R.color.text));
            editHeaderValue.setHintTextColor(getColor(R.color.text_muted));
            editHeaderValue.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            editHeaderValue.setBackground(null);
            editHeaderValue.setPadding(0, dp(4), 0, dp(6));
            editHeaderValue.setSingleLine(true);
            headerRowCard.addView(editHeaderValue);

            // 移除按钮
            TextView btnRemove = new TextView(this);
            btnRemove.setText(t("移除此请求头", "Remove Header"));
            btnRemove.setTextColor(getColor(R.color.err));
            btnRemove.setTextSize(12);
            btnRemove.setGravity(Gravity.END);
            btnRemove.setPadding(0, dp(4), 0, 0);
            btnRemove.setOnClickListener(v -> {
                captureHeaders();
                draft.headers.remove(index);
                renderHeaders();
            });
            headerRowCard.addView(btnRemove);

            headerFields.add(new EditText[]{editHeaderName, editHeaderValue});
            layoutHeadersList.addView(headerRowCard, hrcLp);
        }
    }

    private void renderModels() {
        layoutModelsList.removeAllViews();

        if (draft.modelList != null) {
            for (int i = 0; i < draft.modelList.size(); i++) {
                final int index = i;
                JsonObject model = draft.modelList.get(i).getAsJsonObject();
                String modelId = s(model, "id");
                String modelName = s(model, "name");
                if (modelName.isEmpty()) {
                    modelName = t("模型容量与多模态", "Capacity & Modalities");
                }

                if (layoutModelsList.getChildCount() > 0) {
                    View divider = new View(this);
                    LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
                    dlp.leftMargin = dp(66);
                    divider.setLayoutParams(dlp);
                    divider.setBackgroundColor(getColor(R.color.line_soft));
                    layoutModelsList.addView(divider);
                }

                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(16), dp(12), dp(16), dp(12));
                row.setBackgroundResource(R.drawable.bg_card_clickable);
                row.setClickable(true);
                row.setFocusable(true);

                FrameLayout iconBox = new FrameLayout(this);
                iconBox.setBackgroundResource(R.drawable.bg_settings_icon_box);
                ImageView iconView = new ImageView(this);
                iconView.setImageResource(R.drawable.ic_cpu);
                iconView.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.primary)));
                iconBox.addView(iconView, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
                row.addView(iconBox, new LinearLayout.LayoutParams(dp(38), dp(38)));

                LinearLayout textCol = new LinearLayout(this);
                textCol.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
                tcLp.leftMargin = dp(12);

                TextView tvId = new TextView(this);
                tvId.setText(modelId);
                tvId.setTextColor(getColor(R.color.text));
                tvId.setTextSize(15);
                tvId.setTypeface(null, android.graphics.Typeface.BOLD);
                textCol.addView(tvId);

                TextView tvSub = new TextView(this);
                tvSub.setText(modelName);
                tvSub.setTextColor(getColor(R.color.text_muted));
                tvSub.setTextSize(12);
                textCol.addView(tvSub);

                row.addView(textCol, tcLp);

                // 删除按钮
                TextView btnDel = new TextView(this);
                btnDel.setText(t("删除", "Delete"));
                btnDel.setTextColor(getColor(R.color.err));
                btnDel.setTextSize(12);
                btnDel.setTypeface(null, android.graphics.Typeface.BOLD);
                btnDel.setGravity(Gravity.CENTER);
                btnDel.setBackgroundResource(R.drawable.bg_btn_danger_small);
                btnDel.setPadding(dp(10), dp(4), dp(10), dp(4));
                LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28));
                delLp.rightMargin = dp(8);
                btnDel.setOnClickListener(v -> confirmDeleteModel(index, modelId));
                row.addView(btnDel, delLp);

                ImageView chevron = new ImageView(this);
                chevron.setImageResource(R.drawable.ic_chevron_right);
                chevron.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.text_muted)));
                row.addView(chevron, new LinearLayout.LayoutParams(dp(18), dp(18)));

                row.setOnClickListener(v -> editModel(index));
                layoutModelsList.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }

        if ("llm-pi-ai".equals(s(draft.entry, "settingsNs"))) {
            btnFetchModels.setVisibility(View.VISIBLE);
            btnFetchModels.setEnabled(!Boolean.TRUE.equals(repository.busy.getValue()));
        } else {
            btnFetchModels.setVisibility(View.GONE);
        }
    }

    private void confirmDeleteModel(int index, String modelId) {
        new DshaDialogBuilder(this)
                .setTitle(t("移除模型？", "Remove model?"))
                .setMessage(t("确定从目录中移除模型「" + modelId + "」吗？保存后生效。",
                        "Remove model " + modelId + " from catalog? Effective after save."))
                .setNegativeButton(t("取消", "Cancel"), null)
                .setPositiveButton(t("移除", "Remove"), (dialog, which) -> {
                    if (index >= 0 && index < draft.modelList.size()) {
                        draft.modelList.remove(index);
                        renderModels();
                        updateStatusBanner(t("已从目录移除模型 " + modelId, "Removed model " + modelId));
                    }
                })
                .show();
    }

    private void confirmDeleteProvider() {
        new DshaDialogBuilder(this)
                .setTitle(t("删除服务商？", "Delete provider?"))
                .setMessage(t("确定要删除此服务商配置吗？这会从 DSH 中移除该提供方及关联密钥。",
                        "Delete this provider? This removes the configuration and stored API key."))
                .setNegativeButton(t("取消", "Cancel"), null)
                .setPositiveButton(t("删除", "Delete"), (d, w) -> {
                    JsonArray path = draft.custom
                            ? ModelConfiguration.path("providers", draft.route)
                            : draft.entry.getAsJsonArray("settingsPath");
                    String ref = s(draft.value, "apiKeyEnv");
                    if (ref.isEmpty() && !draft.route.isEmpty()) {
                        ref = ModelConfiguration.keyReference(draft.route);
                    }
                    repository.deleteProvider(
                            s(draft.entry, "settingsNs"),
                            path,
                            draft.revision,
                            draft.generation,
                            ref);
                    draft.clear();
                    showDirectory();
                })
                .show();
    }

    private boolean isConfiguredRoute(String r) {
        if (r == null || r.isEmpty() || current == null) return false;
        JsonArray provs = current.getAsJsonArray("providers");
        if (provs == null) return false;
        for (JsonElement item : provs) {
            if (r.equals(s(item.getAsJsonObject(), "provider"))) return true;
        }
        return false;
    }

    // =========================================================================
    // 下级页面 1: 模型详情编辑 (采用 Skia ModernCardView 现代弹窗)
    // =========================================================================

    private void editModel(int index) {
        JsonObject original = (index < 0 || draft.modelList == null || index >= draft.modelList.size())
                ? new JsonObject()
                : draft.modelList.get(index).getAsJsonObject().deepCopy();

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_model_detail, null);
        AlertDialog dialog = new DshaDialogBuilder(this).setView(dialogView).create();

        TextView tvTitle = dialogView.findViewById(R.id.tv_dialog_model_title);
        EditText editId = dialogView.findViewById(R.id.edit_dialog_model_id);
        EditText editName = dialogView.findViewById(R.id.edit_dialog_model_name);
        EditText editContext = dialogView.findViewById(R.id.edit_dialog_model_context);
        EditText editTokens = dialogView.findViewById(R.id.edit_dialog_model_tokens);
        DshaToggle toggleVision = dialogView.findViewById(R.id.toggle_dialog_model_vision);
        View rowVision = dialogView.findViewById(R.id.row_dialog_model_vision);
        TextView tvError = dialogView.findViewById(R.id.tv_dialog_model_error);
        Button btnCancel = dialogView.findViewById(R.id.btn_dialog_model_cancel);
        Button btnSave = dialogView.findViewById(R.id.btn_dialog_model_save);
        Button btnDelete = dialogView.findViewById(R.id.btn_dialog_model_delete);

        tvTitle.setText(index < 0 ? t("添加模型", "Add Model") : t("模型详情", "Model Details"));
        editId.setText(s(original, "id"));
        editName.setText(s(original, "name"));
        editContext.setText(s(original, "contextWindow"));
        editTokens.setText(s(original, "maxTokens"));

        String inputField = deepseek() ? "inputModalities" : "input";
        boolean hasVision = original.has(inputField) && original.get(inputField).toString().contains("\"image\"");
        toggleVision.setChecked(hasVision);
        if (rowVision != null) {
            rowVision.setOnClickListener(v -> toggleVision.toggle());
        }

        if (index >= 0) {
            btnDelete.setVisibility(View.VISIBLE);
            btnDelete.setOnClickListener(v -> {
                draft.modelList.remove(index);
                renderModels();
                dialog.dismiss();
            });
        } else {
            btnDelete.setVisibility(View.GONE);
        }

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnSave.setOnClickListener(v -> {
            try {
                JsonObject next = original.deepCopy();
                String idStr = editId.getText().toString().trim();
                if (idStr.isEmpty()) {
                    throw new IllegalArgumentException("ID_EMPTY");
                }
                next.addProperty("id", idStr);
                setOptional(next, "name", editName.getText().toString().trim());

                String ctxStr = editContext.getText().toString().trim();
                if (ctxStr.isEmpty()) next.remove("contextWindow");
                else next.addProperty("contextWindow", Long.parseLong(ctxStr));

                String tokStr = editTokens.getText().toString().trim();
                if (tokStr.isEmpty()) next.remove("maxTokens");
                else next.addProperty("maxTokens", Long.parseLong(tokStr));

                boolean prior = original.has(inputField) && original.get(inputField).toString().contains("\"image\"");
                if (toggleVision.isChecked() != prior || index < 0) {
                    next.add(inputField, toggleVision.isChecked()
                            ? ModelConfiguration.path("text", "image")
                            : ModelConfiguration.path("text"));
                }

                JsonArray checked = draft.modelList.deepCopy();
                if (index < 0) checked.add(next);
                else checked.set(index, next);

                ModelConfiguration.validateModels(checked.toString(), true);
                draft.modelList = checked;
                renderModels();
                ToastHelper.makeText(this, t("模型已加入草稿，请点击主页面底部的「保存并同步」生效",
                        "Model added to draft. Tap Save below to sync."), Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            } catch (RuntimeException invalid) {
                tvError.setVisibility(View.VISIBLE);
                tvError.setText(t("请检查模型 ID、重复项和正整数容量。", "Check model ID, duplicates and positive integer capacities."));
            }
        });

        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    // =========================================================================
    // 下级页面 2: 高级配置 JSON 编辑 (采用 Skia ModernCardView 现代弹窗)
    // =========================================================================

    private void advanced() {
        capture();
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_model_advanced, null);
        AlertDialog dialog = new DshaDialogBuilder(this).setView(dialogView).create();

        EditText editJson = dialogView.findViewById(R.id.edit_dialog_advanced_json);
        TextView tvError = dialogView.findViewById(R.id.tv_dialog_advanced_error);
        Button btnCancel = dialogView.findViewById(R.id.btn_dialog_advanced_cancel);
        Button btnSave = dialogView.findViewById(R.id.btn_dialog_advanced_save);

        JsonObject extra = draft.extra != null ? draft.extra.deepCopy() : draft.original.deepCopy();
        for (String k : List.of("baseURL", "api", "protocol", "displayName", "apiKeyEnv", "models", "headers")) {
            extra.remove(k);
        }

        editJson.setText(new GsonBuilder().setPrettyPrinting().create().toJson(extra));

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnSave.setOnClickListener(v -> {
            try {
                JsonObject value = JsonParser.parseString(editJson.getText().toString()).getAsJsonObject();
                for (String k : List.of("baseURL", "api", "protocol", "displayName", "apiKeyEnv", "models", "headers")) {
                    if (value.has(k)) throw new IllegalArgumentException();
                }
                draft.extra = value;
                dialog.dismiss();
                ToastHelper.makeText(this, t("已应用高级配置到草稿", "Applied advanced settings to draft"), Toast.LENGTH_SHORT).show();
            } catch (RuntimeException invalid) {
                tvError.setVisibility(View.VISIBLE);
                tvError.setText(t("请输入有效 JSON 对象，连接、请求头、模型和密钥请在主表单修改。",
                        "Enter a valid JSON object. Edit connection, headers, models and keys in the main form."));
            }
        });

        dialog.show();
    }

    // =========================================================================
    // 下级页面 4: 可用模型自动发现多选合并 (采用 Skia ModernCardView 现代弹窗)
    // =========================================================================

    private void fetchModels() {
        capture();
        try {
            if (draft.endpoint.isEmpty() && draft.route.isEmpty()) {
                throw new IllegalArgumentException("URL");
            }
            ModelConfiguration.validateUrl(draft.endpoint, false);
            if (!draft.key.isEmpty() && !draft.key.matches("[\\x21-\\x7E]+")) {
                throw new IllegalArgumentException("KEY");
            }
            ToastHelper.makeText(this, t("正在连接服务商获取可用模型…", "Fetching available models..."), Toast.LENGTH_SHORT).show();
            repository.discoverModels(
                    s(draft.entry, "settingsNs"), draft.route, draft.endpoint, draft.protocol, draft.key);
        } catch (RuntimeException invalid) {
            String code = String.valueOf(invalid.getMessage());
            String msg = code.equals("KEY")
                    ? t("API Key 不应包含空格或中文换行。", "API keys must not contain whitespace or invalid characters.")
                    : t("请先填写有效的 HTTP/HTTPS API 地址。", "Enter a valid HTTP/HTTPS endpoint first.");
            updateStatusBanner(msg);
            ToastHelper.makeText(this, msg, Toast.LENGTH_LONG).show();
            new DshaDialogBuilder(this)
                    .setTitle(t("获取模型提示", "Notice"))
                    .setMessage(msg)
                    .setPositiveButton(t("知道了", "OK"), null)
                    .show();
        }
    }

    private void showDiscoveredModels(DshModelRepository.ModelDiscovery result) {
        long serial = result.serial;
        JsonArray found = result.models;
        if (found == null || found.isEmpty()) {
            new DshaDialogBuilder(this)
                    .setTitle(t("未发现模型", "No models found"))
                    .setMessage(t("服务商没有返回可用模型。现有模型目录保持不变。",
                            "The provider returned no available models. The current catalog is unchanged."))
                    .setPositiveButton(t("关闭", "Close"), (dialog, which) -> draft.consumeDiscovery(serial))
                    .setOnCancelListener(dialog -> {
                        if (!isChangingConfigurations()) draft.consumeDiscovery(serial);
                    })
                    .show();
            return;
        }

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_model_discovery, null);
        AlertDialog dialog = new DshaDialogBuilder(this).setView(dialogView).create();

        LinearLayout listContainer = dialogView.findViewById(R.id.layout_dialog_discovery_list);
        TextView tvCount = dialogView.findViewById(R.id.tv_dialog_discovery_count);
        TextView btnToggleAll = dialogView.findViewById(R.id.btn_dialog_discovery_toggle_all);
        Button btnCancel = dialogView.findViewById(R.id.btn_dialog_discovery_cancel);
        Button btnMerge = dialogView.findViewById(R.id.btn_dialog_discovery_merge);

        tvCount.setText(t("共发现 " + found.size() + " 个模型", "Found " + found.size() + " models"));

        Set<String> known = new HashSet<>();
        if (draft.modelList != null) {
            for (JsonElement item : draft.modelList) {
                if (item.isJsonObject()) known.add(s(item.getAsJsonObject(), "id"));
            }
        }

        List<CheckBox> checkBoxes = new ArrayList<>();
        listContainer.removeAllViews();

        for (int i = 0; i < found.size(); i++) {
            JsonObject candidate = found.get(i).getAsJsonObject();
            String id = s(candidate, "id");
            String display = s(candidate, "name");
            boolean exists = known.contains(id);

            LinearLayout itemRow = new LinearLayout(this);
            itemRow.setOrientation(LinearLayout.HORIZONTAL);
            itemRow.setGravity(Gravity.CENTER_VERTICAL);
            itemRow.setPadding(dp(8), dp(10), dp(8), dp(10));
            itemRow.setBackgroundResource(R.drawable.bg_card_clickable);

            CheckBox cb = new CheckBox(this);
            cb.setChecked(true);
            checkBoxes.add(cb);
            itemRow.addView(cb);

            LinearLayout textCol = new LinearLayout(this);
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            tcLp.leftMargin = dp(8);

            TextView tvId = new TextView(this);
            tvId.setText(id + (exists ? t(" (已在目录)", " (already exists)") : ""));
            tvId.setTextColor(exists ? getColor(R.color.text_muted) : getColor(R.color.text));
            tvId.setTextSize(14);
            tvId.setTypeface(null, android.graphics.Typeface.BOLD);
            textCol.addView(tvId);

            if (!display.isEmpty() && !display.equals(id)) {
                TextView tvDisplay = new TextView(this);
                tvDisplay.setText(display);
                tvDisplay.setTextColor(getColor(R.color.text_secondary));
                tvDisplay.setTextSize(12);
                textCol.addView(tvDisplay);
            }

            itemRow.addView(textCol, tcLp);
            itemRow.setOnClickListener(v -> cb.setChecked(!cb.isChecked()));

            listContainer.addView(itemRow);
        }

        btnToggleAll.setOnClickListener(v -> {
            boolean anyUnchecked = false;
            for (CheckBox cb : checkBoxes) {
                if (!cb.isChecked()) {
                    anyUnchecked = true;
                    break;
                }
            }
            for (CheckBox cb : checkBoxes) {
                cb.setChecked(anyUnchecked);
            }
        });

        btnCancel.setOnClickListener(v -> {
            draft.consumeDiscovery(serial);
            dialog.dismiss();
        });

        btnMerge.setOnClickListener(v -> {
            Set<String> picked = new LinkedHashSet<>();
            for (int i = 0; i < found.size(); i++) {
                if (checkBoxes.get(i).isChecked()) {
                    picked.add(s(found.get(i).getAsJsonObject(), "id"));
                }
            }
            int before = draft.modelList != null ? draft.modelList.size() : 0;
            draft.modelList = ModelConfiguration.mergeDiscoveredModels(
                    draft.modelList != null ? draft.modelList : new JsonArray(), found, picked);
            renderModels();
            int added = draft.modelList.size() - before;
            draft.consumeDiscovery(serial);
            String banner = (added == 0)
                    ? t("所选模型已在目录中，现有详情保持不变。", "The selected models are already in the catalog.")
                    : t("已将 " + added + " 个模型合并到草稿；请点击下方「保存并同步」生效。",
                    "Added " + added + " models to draft. Tap Save below to sync.");
            updateStatusBanner(banner);
            ToastHelper.makeText(this, banner, Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        dialog.setOnCancelListener(d -> {
            if (!isChangingConfigurations()) draft.consumeDiscovery(serial);
        });

        dialog.show();
    }

    // =========================================================================
    // 保存并同步逻辑
    // =========================================================================

    private void save() {
        capture();
        try {
            if (draft.custom) {
                if (draft.route == null || draft.route.isEmpty()) {
                    throw new IllegalArgumentException("ROUTE_EMPTY");
                }
                ModelConfiguration.validateRoute(draft.route);
                for (JsonElement entry : current.getAsJsonArray("providers")) {
                    if (draft.route.equals(s(entry.getAsJsonObject(), "provider"))) {
                        throw new IllegalArgumentException("ROUTE_TAKEN");
                    }
                }
            }
            if (draft.custom && (draft.endpoint == null || draft.endpoint.isEmpty())) {
                throw new IllegalArgumentException("URL_EMPTY");
            }
            ModelConfiguration.validateUrl(draft.endpoint, draft.custom);

            if (draft.custom && (draft.modelList == null || draft.modelList.isEmpty())) {
                throw new IllegalArgumentException("MODEL_REQUIRED");
            }
            ModelConfiguration.validateModels(draft.modelList.toString(), draft.custom);

            if (!draft.key.isEmpty() && !draft.key.matches("[\\x21-\\x7E]+")) {
                throw new IllegalArgumentException("KEY");
            }
            if (draft.custom && draft.protocol.isEmpty()) {
                throw new IllegalArgumentException("PROTOCOL");
            }

            ToastHelper.makeText(this, t("正在提交并保存配置…", "Saving configuration..."), Toast.LENGTH_SHORT).show();
            JsonObject next = draft.original.deepCopy();
            if (draft.extra != null) {
                for (String k : new ArrayList<>(next.keySet())) {
                    if (!List.of("baseURL", "api", "protocol", "displayName", "apiKeyEnv", "models", "headers")
                            .contains(k)) next.remove(k);
                }
                draft.extra.entrySet().forEach(e -> next.add(e.getKey(), e.getValue()));
            }
            if (!deepseek()) {
                JsonObject headers = ModelConfiguration.headers(draft.headers);
                if (!headers.equals(ModelConfiguration.object(draft.value.get("headers")))) {
                    next.add("headers", headers);
                }
            }
            updateField(next, "baseURL", draft.endpoint);
            updateField(next, deepseek() ? "protocol" : "api", draft.protocol);
            if (!deepseek()) updateField(next, "displayName", draft.name);
            if (draft.custom
                    || !Objects.equals(draft.value.get("models"), draft.modelList)
                    && (draft.value.has("models") || !draft.modelList.isEmpty())) {
                next.add("models", draft.modelList.deepCopy());
            }
            String ref = s(draft.value, "apiKeyEnv");
            if (!draft.key.isEmpty()) {
                ref = ModelConfiguration.keyReference(draft.route);
                next.addProperty("apiKeyEnv", ref);
            }
            JsonArray path = draft.custom
                    ? ModelConfiguration.path("providers", draft.route)
                    : draft.entry.getAsJsonArray("settingsPath");
            JsonArray ops = ModelConfiguration.diff(path, draft.original, next);
            if (draft.custom
                    || ops.isEmpty()
                    && !deepseek()
                    && ModelConfiguration.at(namespace(s(draft.entry, "settingsNs")).get("value"), path).isJsonNull()) {
                JsonObject op = new JsonObject();
                op.addProperty("op", "set");
                op.add("path", path);
                op.add("value", next);
                ops = new JsonArray();
                ops.add(op);
            }
            repository.save(
                    s(draft.entry, "settingsNs"), ops, draft.revision, draft.generation, ref, draft.key);
        } catch (RuntimeException invalid) {
            String code = String.valueOf(invalid.getMessage());
            String msg = code.equals("ROUTE_EMPTY")
                    ? t("请填写服务商唯一标识（如 my-api，只能由小写英文与数字组成）。", "Enter provider ID (lowercase).")
                    : code.equals("ROUTE_TAKEN")
                    ? t("该提供方标识已存在，请更换标识名称。", "Provider ID is already taken.")
                    : code.equals("URL_EMPTY")
                    ? t("请填写服务商的 API 地址（例如 https://api.openai.com/v1）。", "Enter API endpoint.")
                    : code.equals("MODEL_REQUIRED")
                    ? t("模型目录不能为空。请先点击「添加模型」录入模型 ID，或点击「获取可用模型」自动导入。", "Model catalog is empty. Please add a model first.")
                    : code.startsWith("HEADERS")
                    ? t("请检查请求头：名称不能重复，值不能包含换行，不可覆盖连接与传输字段。", "Check headers.")
                    : code.startsWith("ROUTE")
                    ? t("提供方标识需以小写字母开头，用短横线连接（如 my-provider）。", "Provider IDs must start with lowercase letter.")
                    : code.equals("URL")
                    ? t("请填写有效的 HTTP/HTTPS API 地址。", "Enter valid HTTP/HTTPS endpoint.")
                    : code.equals("KEY")
                    ? t("API Key 包含非法字符，请检查是否有多余空格或非 ASCII 字符。", "API key contains invalid characters.")
                    : code.equals("PROTOCOL")
                    ? t("请选择连接协议（如 OpenAI Chat Completions 或 Anthropic）。", "Select a protocol.")
                    : t("请检查模型目录：ID 不能重复，容量必须是正整数。", "Check models: unique IDs and positive integer capacities are required.");
            updateStatusBanner(msg);
            ToastHelper.makeText(this, msg, Toast.LENGTH_LONG).show();
            new DshaDialogBuilder(this)
                    .setTitle(t("保存失败", "Save failed"))
                    .setMessage(msg)
                    .setPositiveButton(t("去修改", "OK"), null)
                    .show();
        }
    }

    private void updateField(JsonObject next, String key, String value) {
        if (!value.equals(s(draft.value, key))) setOptional(next, key, value);
    }

    private static void setOptional(JsonObject object, String key, String value) {
        if (value.isEmpty()) object.remove(key);
        else object.addProperty(key, value);
    }

    private static String s(JsonObject object, String key) {
        return ModelConfiguration.text(object, key);
    }

    private static String t(String zh, String en) {
        return UiText.choose(zh, en);
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onPause() {
        capture();
        super.onPause();
    }
}
