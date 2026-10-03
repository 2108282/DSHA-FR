package com.deepseekharness.app.ui.contract;

/**
 * 启动页用户行为契约：所有界面按钮及交互行为的统一定义。
 */
public interface LaunchActions {
    /** 主操作按钮点击（启动 / 进入 / 同步凭据） */
    void onPrimaryActionClick();

    /** 强制重启服务 */
    void onRestartClick();

    /** 停止服务 */
    void onStopClick();

    /** 打开快捷对话抽屉 */
    void onOpenSheetClick();

    /** 快速选择端口 */
    void onPortSelect(int port);

    /** 端口输入更新 */
    void onPortInput(String port);

    /** 点击网络地址 / 鉴权凭据卡片 */
    void onLanAddressClick();
}
