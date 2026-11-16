# 手机动作 HTTP 退役迁移

本次桥协议版本由 1 升至 2。手机操作统一由 `mcp__phone__*` 提供；3095 仍是独立设备和运行时桥，不是手机 MCP 的执行后备。

## 已退役入口

鉴权成功后，下列旧入口只返回稳定的文本错误，不读取屏幕、启动 App、修改剪贴板、弹分享面板或执行 Root 后备：

```text
[RETIRED_USE_MCP] <endpoint> 已退役；请使用 <replacement>。本请求未执行手机动作，不提供 HTTP / Root 操作后备。
```

| 旧 HTTP 入口 | 对应手机 MCP |
|---|---|
| `/app/ui/*`（含 dump、tap、input、key、swipe、screenshot、shot） | `screen`、`tap`、`type`、`scroll`、`key`、`tap_xy` |
| `/app/launch`、`/app/open` | `open` |
| `/app/clip` | `clipboard` |
| `/app/share`（文字和文件均退役） | `intent(kind=share)`，文件参数以 MCP schema 为准 |

保留原来的 HTTP 文本响应格式；这是业务错误码，不声称 HTTP 状态已改成 410。请求仍先鉴权，未授权请求保持 `[UNAUTHORIZED]`，不泄露参数内容。兼容入口不代调用某个 Agent 的 MCP，不把旧参数静默转换成另一种动作。

旧 UI 文字点按、任意轨迹滑动、快捷设置/锁屏键、PNG 落地截图不与现有 MCP 参数一一等价。调用方必须按实际工具能力迁移；没有对应功能时返回明确不可用，不能保留旧 HTTP 或 Root 旁路。

## 实现收敛

- 删除旧 HTTP 的独立 UI、启动、剪贴板、文字/文件分享、链接打开执行器及未使用 UI 授权辅助方法。
- 删除 `DshaAccessibilityService` 中只有旧 HTTP 调用的静态 UI 方法与其私有辅助实现。
- 保留无障碍服务继承关系、连接状态、Root 连接、`onServiceConnected`、事件转发、`onInterrupt` 和 `onDestroy`。MCP 主屏驱动继续由父服务提供。
- 保留命令确认租约；它仍服务于 `/confirm` 和原生确认浮层，不再作为第二套 UI 授权层。
- `/app/help` 不再发布旧动作调用示例，也不再给出手机任务失败后改用 ADB/Root 的指导。

## 保留的职责

以下基础设施实现和路由保持原合同：`/app/notify`、`/app/ask`、`/app/task/*`、`/confirm`、`/app/overlay`、`/app/plugins`、`/app/freeze/state`、`/exec`、`/health`。

特别是 HTTP `/app/notify` 负责任务结果通知及运行状态切换，不能等同于 MCP 的普通提醒。`/app/ask` 多选阻塞问答也不等同于手机交接。

设备及文件独有能力保留：`device`、`location`、`sensors/sensor`、`torch`、`vibrate`、`toast`、`readfile`、`export`、`version/help`。它们不因此加入手机操作 MCP。

`/app/apps` 的只读已安装包查询暂保留，以免丢失无 Launcher 入口包的可见性；帮助和指南不再把它作为手机任务找应用/启动的入口。手机任务使用 MCP `apps/open`，范围以其 schema 为准。

## 指南与生效范围

打包指南和运行实体都已清理旧 HTTP dump、启动、剪贴板、分享示例，以及备用手机 Root/ADB 操作引导；本体保活、防砖、外部设备范围规则保留。`PhoneGuest.AGENTS_SECTION` 只修改提示词，转发器、连接与配置代码不变。

本次没有重载插件、停止引擎或替换已安装 APK。运行指南文件的修改不代表当前已加载插件立即重新注入；HTTP 退役需要新 APK 构建并安装后生效。既有会话里已写入的历史提示词不会被本次文件修改删除，后续步骤应按最新规则执行。

## 验证

- 对实际 `retiredPhoneAction` 方法抽取执行 Java 测试：UI 各路径、带参数请求、启动/打开/剪贴板、文字及文件分享均返回 `RETIRED_USE_MCP`；基础设施及独有设备路径不命中。
- 比较保留的基础设施/设备方法与修改前实现，确认方法体未变；检索确认无遗留静态 UI 调用。
- 检查运行与打包指南的 JavaScript 语法，确认不再含旧 HTTP 操作清单和 dump 步骤。
- 完整 Android 集成构建由主代理在并行改动合并后运行。本次不通过在线旧 HTTP 执行动作测试，避免旧 APK 仍执行真实操作。
