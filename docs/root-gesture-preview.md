# 独立底角手势

分支：`root-quickchat-gesture`。基于前端 `magisk-apk`，不依赖小米桌面或 LSPosed 注入。

## 使用

安装云端签名 APK，进入 QuickChat 设置 → 交互与手势。在“圈定即搜重定向”下方开启“独立底角手势”，按 Root 管理器提示授权。副标题显示实际状态，授权失败或 Root 服务退出时开关自动回落。整行和右侧 DshaToggle 均可操作。

从左右底角向屏幕内拖动唤起 QuickChat。不显示任何滑动图标、进度环或悬浮窗口，本功能不再申请悬浮窗权限。其他功能原有的悬浮窗权限不受影响。

## 生命周期

默认关闭，用户开启后保存设置。取消了首版 30 分钟限制；APK 下次启动时按已保存的开关恢复。关闭开关会发送 STOP，释放输入通道并结束 Root 子进程。

Root helper 仍由 APK 持有进程管道。无需页面保持前台，但 APK 被彻底杀死后 helper 通过 EOF 退出；下次 APK 启动重新建立监听。后台存活沿用 DSH 现有机制，没有新增独立前台服务、开机模块或轮询看门狗。没有申请 WakeLock。

仅竖屏、屏幕点亮且解锁时接受新手势。原助手手势由使用者自行关闭。横屏、折叠屏、多用户暂未提供。

## 识别规则

参考已安装小米桌面 FsGestureAssistHelper 的行为，采用独立实现：左右角宽 48dp，底部热区高 25dp，DOWN 到 MOVE 的欧氏位移达到 55dp 即触发，每个触摸序列只触发一次，无额外速度、角度条件。小米隐藏手势条的 20.5dp 热区暂不自动切换，识别器已有自定义底高构造。

## 架构

- `RootGestureMain`：uid 0 的独立 app_process，通过 InputManager.monitorGestureInput 接收默认屏幕触摸，仅接管底角起始的流。没有逐帧进度通信，只输出状态、触发和失败消息；stdin EOF/STOP 退出。
- `CornerGestureRecognizer`：无 Android 依赖的纯 Java 状态机。
- `CornerGesturePreview`：APK 内的 Root 会话管理器，管理开关、状态通知、启动失败回落及恢复；不绘制 UI、不创建 overlay。
- `ConfigStore`：保存用户开关；`DshaApp` 在 APK 启动时恢复。
- QuickChat：Root 端以 null ApplicationThread 调用 IActivityTaskManager.startActivityAsUser，启动已有 AssistGatewayActivity，沿用纯净／特效模式路由。
- 隐藏 InputChannel/InputEventReceiver 签名位于 tools/gesture-stubs，仅 compileOnly，不进入 APK。

## 构建与验证

使用原有 Gradle／云端签名流程，GitHub Actions 先执行 `:app:testCornerGesture` 再构建 release APK。该任务只编译纯 Java 识别器及其 JUnit 测试，避免仓库旧更新测试缺失依赖阻碍本功能验证。

ARM64 的 tools/build-gesture-preview.py 仅用于 Java/DEX 增量验证，不替代正式 Gradle 构建；修改资源后应使用正式云端产物。`--unsigned` 不可安装。

无输入探测：

```sh
CLASSPATH=/path/to/apk app_process /system/bin com.deepseekharness.app.gesture.RootGestureMain --probe
```

成功输出 READY 后释放通道退出。从 Linux chroot 测试需继承 Android boot classpath 和 APEX 环境。

首版 Android 16 / HyperOS OS3.0 实测：Root monitor 创建／释放、左右底角单次触发、短距离取消和 STOP 退出通过。纯 Java 识别器 19 项 JUnit 测试通过；用户确认首版实际效果可用。第二版移除视觉反馈、加入卡片开关及持久设置。第三版补回达到 55dp 阈值时的一次 VIRTUAL_KEY 触觉反馈：Root 进程直接调用系统 Vibrator.performHapticFeedback，flags=0，由系统设置和 OEM 映射决定实际波形；旧系统无该接口时使用遵循触觉开关的 predefined click。短距离取消不震动，触发后继续拖动不重复震动。
