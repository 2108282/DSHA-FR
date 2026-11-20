# 手机操作 MCP：功能与扩展契约

适用源码版本：0.2.2-rc.3-phone-tasks-1 / versionCode 161。安装该版本APK并重新连接phone MCP后才生效；旧APK不会因Node指南更新自动获得新工具。

## 定位与架构

此MCP面向这台实体Android手机的数据、系统功能和App界面任务，不是完整设备监控/运维接口。工具已挂载不代表当前任务应调用它。先判断任务目标和数据来源，再选择工具：电话、短信、联系人、日历、媒体、闹钟和计时器直接走原生工具，不先读屏、不在原生返回后自动读屏；结果依据原生状态/回查/回执。

`screen`仅在用户要求查看手机当前界面，或完成手机任务确需当前界面信息时使用；允许只观察而不点击。源码、文件、终端、网页DOM、JavaScript、localStorage、插件状态和诊断记录使用对应开发或服务接口；它们运行在手机/WebView中不构成调用phone的理由。已有截图直接用图像工具分析。提到“页面、前端、APK、WebView、面板、记录”，或其他工具缺失/失败，都不能单独触发手机操作。用户明确要求观察DSH在手机上的实际视觉效果时，可以进入手机观察流程，但仍遵守访问策略。

`open`为手机App/链接入口，可自动观察并返回新屏幕；点击、输入、滚动、系统键和handoff继续后也可能附带屏幕，不能把这些返回误当成原生业务验证。GUI兜底只适用于已确认属于手机App的流程。空屏、不可读和旧观察失效先核对当前界面是否仍为任务目标且观察必要，不自动升级截图或交接；截图不能读取插件内部存储。`handoff`仅用于用户接手手机流程，普通澄清使用问答工具。权限拒绝或写操作未确认不自动切换通道重做。

当前架构：DSH的MCP客户端 → Node relay（透明转发）→ APK McpServer/PhoneCore → 原生任务执行器或主屏/后台屏Driver。工具目录、任务状态、确认和Android执行器目前在APK；因此更新这些代码需要APK构建。后续可将业务编排移到Node，但必须保留APK中的Android权限、任务令牌和动作二次校验，不能让远端工具参数绕过它们。

## 实际工具（17个）

| 工具 | 功能 |
|---|---|
| apps | 找可启动App |
| open | 打开App/有效链接，等待目标包界面，支持后台屏 |
| intent | 固定手机事务，详见下表 |
| screen | 同次快照的主/后台屏树、可选标号截图 |
| tap | 编号点击/长按，执行前复查 |
| type | 替换/清空文字，2000字上限；written与submitted分别返回 |
| scroll | 主屏或指定列表滚动，拒绝过期观察 |
| collect | 指定列表采集，返回complete/truncated/stop_reason，不夸称无限列表全量 |
| key | back/home/recents/notifications |
| tap_xy | 最近截图坐标后备，按现有用户确认模式执行 |
| handoff | 后台任务呈现并核验后交给用户，继续前重读主屏 |
| clipboard | 当前文本读写，受系统前台/焦点规则约束 |
| notify | 普通任务提醒，不结束引擎任务 |
| contacts | search/get联系人及真实号码，返回多候选/多号码情况 |
| messages | threads按明确号码或地址关键词找会话；search/read指定thread_id |
| calendar | 可写日历、事件/实例查询、创建/批量/改/删与提醒事务 |
| media | 按文件名搜索images/audio/video/files，为分享提供content URI |

### intent事务

- alarm / timer：标准Android时钟Intent。仅证明请求提交，不保证系统时钟已保存。补充SET_ALARM普通权限。
- dial：打开拨号盘；call：实际向Telecom提交拨号。call需request_id和通话权限；多账户时先accounts并明确phone_account_id；不把请求成功当接通。
- sms_draft：短信草稿；sms_send：标准SmsManager发送，必须明确subscription_id及request_id。accounts可列可用SIM；operation_status查询分段sent/delivery回执。不自动重发未知或部分失败短信。
- calendar：保留旧新建表单入口；日历事务优先使用calendar工具。
- share：文字和/或外部存储文件/受控content URI，使用临时URI读授权；出现分享页不等于内容已发出。
- navigate：Android geo地点链接，之后路线/开始导航可能需要GUI。
- settings：固定系统页面直达；不开放任意设置键写入。
- accounts：返回通话账户与活动SIM，无实际拨打/发送。
- operation_status：查询同request_id记录，不产生写操作。

电话/短信/联系人/日历/媒体权限通过“设置→手机操控→原生任务权限”由用户按任务手动授予。工具不静默授予权限，不因权限不足改走Root或GUI。短信等权限可能受系统限制；缺失明确返回denied。

日历使用原始calendar_id/event_id；批量同日历最多30条；重复规则写入/更新不支持，删除系列需scope=series。全天日期用UTC零点、排他结束日期；普通时间带偏移。验证只覆盖事件字段/提醒，不证明云同步或提醒实际响铃。

## 结果与防重复

structuredContent与文字来自同一结果。dispatched=请求发出；observed=目标界面/查询被观察；completed及verified=声明范围回查通过；unconfirmed=副作用可能已发生，先核对；partial=采集不完整；denied/unsupported/failed区别返回。

原生call/sms_send和日历写操作要求request_id；同ID同参数回放既有状态，同ID不同参数拒绝。持久化attempt标记用于抑制重放，不宣称跨系统崩溃exactly-once。操作记录不要随意清空后据“没有记录”重做。SMS sent回执与delivery PDU分别校验；无送达回执不意味着未发送。

主屏旧观察、后台旧token/owner/window/完整目标摘要不匹配均拒绝。确认后执行前再次检查；停止、锁屏、服务重连或人机交接使旧目标失效。后台协议v2，旧新两端不兼容时不执行写动作。

购物/订票等没有跨App标准购买API，使用确定有效的入口/深链再GUI。订单结果需订单详情等证据；登录、验证码、支付等按已有规则handoff。

## 旧HTTP与独立设备桥

/app/ui/*、/app/launch、/app/open、/app/clip、/app/share返回RETIRED_USE_MCP，旧独立Root/无障碍执行器已移除。/app/apps暂保留安装包只读差异，不作为打开App后备。

保留task通知/confirm/overlay/plugins/freeze/exec/health和设备、传感器、导出服务。它们不进入本MCP目录。特别是HTTP /app/notify仍有任务结束职责，MCP notify仅普通提醒；/app/ask与handoff语义不同。

## 后续新增功能/API设计

1. **先定义用户任务。** 说明输入、对象ID、预期结果和验证范围。属于设备诊断/运维的留在独立服务，不塞进phone目录。
2. **选择实现层。** 公共SDK/标准Provider优先；固定AOSP特权适配需能力探测；无稳定API才GUI。不暴露任意Intent component/extras、SQL、Provider URI或Shell字符串。
3. **先做业务契约再工具名。** 在领域Facade提供固定action，参数校验、权限预检查与查询准备完成后，通过统一任务guard执行写入。尽量扩领域工具action，只有职责独立才新增顶层工具。
4. **查询与写入分离。** 提供对象发现和原始ID路径；多联系人、多SIM、多日历不能猜。写入需要明确request_id并支持状态查询。
5. **复用任务与策略。** 进入PhoneCore的锁屏/暂停/owner/禁止App/确认层；耗时预检查后真正副作用前再检查，不能只在工具入口检查一次。
6. **返回可证实结果。** 定义status/effect/verified/verification_scope/retry_safe。API返回不代表业务完成；异步回执、部分结果和未知状态需要独立处理。
7. **更新全链路。** PhoneToolCatalog schema→Core路由→SystemApi→执行器→Manifest与任务权限UI→输出/日志→指南/文档。协议字段变化升版本并拒绝不兼容写请求。不要为新功能再开HTTP旁路。
8. **验证真实边界。** 单测状态机/防重复/拒绝/取消竞态；协议用假通道验证旧token零写入；Android编译及Manifest验证；真实短信/拨号/交易只在用户安排的测试场景进行。

推荐落点：业务域放phone/tasks，Android执行不依赖模型文本；主后台动作留Driver；Node只做工具接入或未来业务编排。若将来移到Node，定义有版本的内部能力协议和稳定结果schema，APK仍最终验证权限/owner/目标身份，不接受Node任意特权命令。

## 验证与生效

本地：Kotlin与Java编译、Manifest合并、现有纯JVM回归。云端android-build工作流跑完整单测和Release构建，验证发布证书后上传artifact，不创建Release。

手机任务边界的回放检查至少覆盖以下场景；这些是模型行为评估用例，不是字符串检查通过即可保证的结果：

| 请求/情况 | 期望路由 |
|---|---|
| 读取流式插件已采集的3条诊断记录 | 插件/服务接口或已导出文件，不调用phone |
| 检查APK源码、WebView代码或DSH页面DOM/状态 | 文件、终端、对应开发接口，不调用screen |
| 用户已经提供截图，要求分析布局 | 图像读取，不读取手机当前屏幕 |
| 其他工具不可用或取不到localStorage | 说明实际能力或补齐授权范围内的数据通路，不改走phone兜底 |
| 用户要求看看手机当前显示什么 | screen，可只观察，无需点击 |
| 用户明确要求查看DSH在手机上的实际显示效果 | 手机观察流程，遵守访问策略，不用截图代替内部状态读取 |
| 设置闹钟、读取手机日历、查询联系人 | 对应原生工具，不先读屏，不用读屏验证 |
| 原生任务权限不足或结果未知 | 原生错误/事务状态回查，不读屏诊断、不换通道重做 |
| 读屏返回桌面/空内容/不可读，且当前界面与任务不相关 | 停止手机操作，不追加截图或handoff |
| 必需的手机App界面文字稀少 | 核对目标和访问策略后按需附图，不无条件升级 |

工具描述和屏幕结果提示在APK中，需安装重新构建的APK并重新连接phone MCP；设备引导同时维护APK资产与底座overlay中的PROMPT段，部署后需重新加载该插件/核心。不要把改源码、语法/编译通过等同于当前会话已加载新规则，也不要把以上期望结果当成已跑过模型行为评测。

尚需明确区分：源码/单测通过不等于所有ROM实机通过；新电话/短信/日历Provider与真实任务移动必须在安装新APK后按受控测试安排验证。本次不执行真实通话、短信、支付或下单回归。
