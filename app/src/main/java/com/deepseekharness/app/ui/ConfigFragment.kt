package com.deepseekharness.app.ui

import android.Manifest
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.deepseekharness.app.R
import com.deepseekharness.app.ui.contract.ConfigAction
import com.deepseekharness.app.ui.contract.ConfigPresenter
import com.deepseekharness.app.ui.contract.ConfigUiState

/**
 * 二级配置页：M3 Expressive 风格与纯单向数据流渲染，全面解耦表单操作与系统权限跳转。
 */
class ConfigFragment : Fragment(), ConfigPresenter.ViewCallback {

    private var presenter: ConfigPresenter? = null

    // 控件引用
    private var subBack: View? = null
    private var workspaceEntry: View? = null
    private var advHeader: View? = null
    private var advBody: View? = null
    private var portInput: EditText? = null
    private var tasksetInput: EditText? = null
    private var confirmShellCheck: CheckBox? = null
    private var lanModeCheck: CheckBox? = null
    private var overlayStreamCheck: CheckBox? = null
    private var overlayStyleBtn: View? = null
    private var translateBtn: View? = null
    private var sensorsCheck: CheckBox? = null
    private var locationCheck: CheckBox? = null
    private var allFilesBtn: View? = null
    private var allFilesStatusText: TextView? = null
    private var saveBtn: Button? = null
    private var batteryOptBtn: View? = null
    private var a11yBtn: View? = null
    private var a11yStatusText: TextView? = null
    private var asrStatusText: TextView? = null
    private var asrContinuousCheck: CheckBox? = null
    private var asrCheckBtn: View? = null
    private var asrFixBtn: View? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_config, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        presenter = ConfigPresenter(requireActivity(), this)

        // 1. 查找控件
        subBack = view.findViewById(R.id.sub_back)
        workspaceEntry = view.findViewById(R.id.config_workspace_entry)
        advHeader = view.findViewById(R.id.config_adv_header)
        advBody = view.findViewById(R.id.config_adv_body)
        portInput = view.findViewById(R.id.config_port)
        tasksetInput = view.findViewById(R.id.config_taskset)
        confirmShellCheck = view.findViewById(R.id.config_confirm_shell)
        lanModeCheck = view.findViewById(R.id.config_lan_mode)
        overlayStreamCheck = view.findViewById(R.id.config_overlay_stream)
        overlayStyleBtn = view.findViewById(R.id.config_overlay_style)
        translateBtn = view.findViewById(R.id.config_translate)
        sensorsCheck = view.findViewById(R.id.config_cap_sensors)
        locationCheck = view.findViewById(R.id.config_cap_location)
        allFilesBtn = view.findViewById(R.id.config_all_files)
        allFilesStatusText = view.findViewById(R.id.config_all_files_status)
        saveBtn = view.findViewById(R.id.config_save)
        batteryOptBtn = view.findViewById(R.id.config_battery_opt)
        a11yBtn = view.findViewById(R.id.config_a11y)
        a11yStatusText = view.findViewById(R.id.config_a11y_status)
        asrStatusText = view.findViewById(R.id.config_asr_status)
        asrContinuousCheck = view.findViewById(R.id.config_asr_continuous)
        asrCheckBtn = view.findViewById(R.id.config_asr_btn_check)
        asrFixBtn = view.findViewById(R.id.config_asr_btn_fix)

        // 2. 挂接折叠动画
        advHeader?.setOnClickListener {
            val isVisible = advBody?.visibility == View.VISIBLE
            advBody?.visibility = if (isVisible) View.GONE else View.VISIBLE
            (advHeader as? TextView)?.text = if (isVisible) "▸ 点击展开高级设置：端口与 CPU 亲和性 (Taskset)" else "▾ 高级设置：端口与 CPU 亲和性 (Taskset)"
        }

        // 3. 事件契约单行派发
        subBack?.setOnClickListener { presenter?.dispatch(ConfigAction.Back) }
        workspaceEntry?.setOnClickListener { presenter?.dispatch(ConfigAction.OpenWorkspace) }
        overlayStyleBtn?.setOnClickListener { presenter?.dispatch(ConfigAction.OpenOverlayStyle) }
        allFilesBtn?.setOnClickListener { presenter?.dispatch(ConfigAction.OpenAllFilesSettings) }
        batteryOptBtn?.setOnClickListener { presenter?.dispatch(ConfigAction.OpenBatteryOptimization) }
        a11yBtn?.setOnClickListener { presenter?.dispatch(ConfigAction.OpenA11ySettings) }
        asrCheckBtn?.setOnClickListener { presenter?.dispatch(ConfigAction.CheckAsrStatus) }
        asrFixBtn?.setOnClickListener { presenter?.dispatch(ConfigAction.FixAsrConfig) }

        translateBtn?.setOnClickListener {
            android.widget.Toast.makeText(requireContext(), "插件市场翻译组件已内置，后续版本开放自定义模型接口", android.widget.Toast.LENGTH_SHORT).show()
        }

        asrContinuousCheck?.setOnCheckedChangeListener { _, checked ->
            presenter?.dispatch(ConfigAction.ToggleAsrContinuous(checked))
        }

        saveBtn?.setOnClickListener {
            presenter?.dispatch(
                ConfigAction.SaveConfig(
                    port = portInput?.text?.toString() ?: "3080",
                    taskset = tasksetInput?.text?.toString() ?: "",
                    confirmShell = confirmShellCheck?.isChecked ?: true,
                    lanMode = lanModeCheck?.isChecked ?: false,
                    overlayStream = overlayStreamCheck?.isChecked ?: false,
                    capSensors = sensorsCheck?.isChecked ?: false,
                    capLocation = locationCheck?.isChecked ?: false
                )
            )
        }

        // 4. 初始化
        presenter?.init()
    }

    override fun onResume() {
        super.onResume()
        // 从外部系统设置或授权管理器返回时，自动刷新系统权限与无障碍状态
        presenter?.refreshState()
    }

    override fun onDestroyView() {
        presenter = null
        subBack = null
        workspaceEntry = null
        advHeader = null
        advBody = null
        portInput = null
        tasksetInput = null
        confirmShellCheck = null
        lanModeCheck = null
        overlayStreamCheck = null
        overlayStyleBtn = null
        translateBtn = null
        sensorsCheck = null
        locationCheck = null
        allFilesBtn = null
        allFilesStatusText = null
        saveBtn = null
        batteryOptBtn = null
        a11yBtn = null
        a11yStatusText = null
        asrStatusText = null
        asrContinuousCheck = null
        asrCheckBtn = null
        asrFixBtn = null
        super.onDestroyView()
    }

    /**
     * 单一渲染入口：纯粹的数据快照向 UI 回填，绝不包含任何业务推导
     */
    override fun onRender(state: ConfigUiState) {
        if (!isAdded || view == null) return

        portInput?.setText(state.port)
        tasksetInput?.setText(state.taskset)
        confirmShellCheck?.isChecked = state.isConfirmShell
        lanModeCheck?.isChecked = state.isLanMode
        overlayStreamCheck?.isChecked = state.isOverlayStream
        sensorsCheck?.isChecked = state.isCapSensors
        locationCheck?.isChecked = state.isCapLocation
        if (asrContinuousCheck?.isChecked != state.isAsrContinuous) {
            asrContinuousCheck?.setOnCheckedChangeListener(null)
            asrContinuousCheck?.isChecked = state.isAsrContinuous
            asrContinuousCheck?.setOnCheckedChangeListener { _, checked ->
                presenter?.dispatch(ConfigAction.ToggleAsrContinuous(checked))
            }
        }

        allFilesStatusText?.text = state.allFilesStatusText
        a11yStatusText?.text = state.a11yStatusText
        asrStatusText?.text = state.asrStatusText
    }

    override fun onOpenWorkspace() {
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, WorkspaceFragment())
            .addToBackStack("workspace")
            .commit()
    }

    override fun onGoBack() {
        parentFragmentManager.popBackStack()
    }

    override fun onRequestLocationPermission() {
        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 104)
    }
}
