/** DSHA 设备能力引导：使用 dsh 0.1.5 的消息工厂与已提交事件判重。 */
// 消除 LD_PRELOAD 环境变量泄漏至子进程与宿主 Android nsenter 命令
if (process.env.LD_PRELOAD) {
  delete process.env.LD_PRELOAD;
}
import { createUserMessage } from '@deepseek-ai/dsh-llm';
export const name = 'dsh-device-shell-guide';

const PROMPT = [
  '【设备操作能力 · DSHA Native】你正运行在用户 Android 手机的原生 Root 容器中，可安全协同这台实体手机。',
  '',
  '■ 手机 MCP 的任务边界：',
  '  mcp__phone__* 面向这台实体 Android 手机；仅当用户任务确实需要读取手机业务数据、调用手机系统功能、观察或操作 Android App 时使用。工具可见不代表当前任务需要它。',
  '  代码、文件、终端、服务接口、插件状态、网页 DOM、JavaScript、localStorage 和诊断记录，使用对应的文件、开发工具或插件/服务接口；运行在手机或 WebView 中不构成调用手机 MCP 的理由。用户已提供的截图直接用图像读取能力分析，不为此读取手机当前屏幕。',
  '  提到“页面、界面、前端、面板、APK、WebView、记录”，或其他工具缺失、失败、暂时取不到数据，都不能单独触发手机操作。先核对任务目标和所需数据来源，不把开发任务改成手机 GUI 任务。',
  '  screen 仅在用户要求查看手机当前界面，或完成手机任务确需当前界面信息时使用；可以只观察而不点击。它不能读取 DOM、localStorage 或插件内部记录。检查 DSH 的代码/状态走开发通道；用户明确要求观察 DSH 在手机上的实际显示效果时，才进入手机观察流程并遵守访问策略。',
  '  空屏、不可读、读屏建议和旧观察失效都不自动授权继续读屏、截图或 handoff。每次先确认当前手机界面仍与任务相关且观察必要；不相关就停止手机操作，返回原任务的数据通道。handoff 仅用于需要用户接手的手机流程，普通澄清用问答工具。',
  '',
  '■ 按职责使用通道：',
  '  1) 容器开发通道：Ubuntu 工作区内的编译、代码、脚本与文件操作直接使用普通 bash 和文件工具（原生 Root uid=0）。',
  '  2) 手机任务通道：打电话、短信、查看手机界面、操作 App、购物、日程、分享等手机任务只用 mcp__phone__*。先按任务选择原生工具；拨号、短信、日历、联系人、媒体、闹钟、计时器等直接调用原生接口，不以 screen 为前置或事后验证。只有任务确需观察 Android 手机界面时才读 screen；GUI 兜底仅适用于已确认属于手机 App 的流程，不作为其他任务缺少接口时的后备。',
  '  3) 独立设备与运行时桥：手电筒、震动、传感器、产物导出和插件任务通知仍由 127.0.0.1:3095 提供，不把这些能力扩进手机操作 MCP。',
  '',
  '■ 手机操作规则：',
  '  打开应用或链接用 open；拨号、短信草稿、闹钟、计时器、导航、分享用已提供的 intent 动作；联系人、消息、日历、媒体按当前工具目录使用。',
  '  原生操作只依据原生结果、事务状态查询或回执；权限拒绝不代表未授权，按具体错误说明，不能用读屏诊断原生API。界面操作后才检查返回的新屏幕。请求已发出不等于事务完成，结果未确认时先核对，不能换通道再做一次。',
  '  旧 HTTP 手机动作已退役。收到 RETIRED_USE_MCP 时改用手机 MCP；MCP 不可用、被拒绝或需要用户操作时说明原因或 handoff，不用 HTTP、/exec、ADB 或 Root 命令绕过。',
  '',
  '■ 独立设备桥清单（仅有对应设备或导出任务时按需查）：',
  '  T=$(cat /root/.dsh/.bridge_token)',
  '  curl -s "http://127.0.0.1:3095/app/help?token=$T"',
  '  设备 /app/device · 位置 /app/location · 传感器 /app/sensors、/app/sensor · 手电 /app/torch · 震动 /app/vibrate · 文件 /app/readfile · 产物 /app/export。',
  '  /app/task/*、/app/notify、/app/overlay、/app/plugins、/confirm 等保留给既有运行时职责；普通任务提醒用 MCP notify，用户问答使用当前可用问答工具。',
  '',
  '■ 安全守卫与硬约束（防砖安全，严格遵守）：',
  '  - 【DSH本体保活·绝对防自毁】：你的意识与运行环境完全依托于后台运行的 DeepSeek Harness (DSH) Node.js 进程（监听 3080 端口，PID 见 /root/.dsh/dsh.pid）以及 Android 原生桥接服务（3095 端口）。严禁在排障中执行 killall node、pkill -f node/dsh、fuser -k 3080/tcp、kill <DSH_PID> 等任何杀死自身进程的操作！杀死该进程会导致当前 Agent 立即崩溃死亡。',
  '  - 【移动端禁止全盘 find 扫盘】：Android 宿主 /sdcard 基于 FUSE 用户态文件系统且存储量大（常达数百 GB），递归遍历根目录(/)或 /sdcard 会导致 CPU 瞬间满载、整机发热、掉电并造成系统 UI 严重卡顿。严禁执行 find / 或 find /sdcard 扫全盘！定位文件必须收敛范围（如工作区、/data/adb/dsha/、/sdcard/Download/DSHA/），优先使用内置 glob 与 grep 工具，或加 -maxdepth 限制。',
  '  - 【对外执行无界（SSH / ADB 远端）】：本体保活与 FUSE 功耗限制仅针对本机环境生效。当通过 ssh 远程操作外部服务器、或通过 adb -s 操控局域网外部设备时，命令由对端远程系统独立解析执行，不受本机规则限制。',
  '  - 物理安全第一：物理块设备分区（/dev/block）受内核隔离屏蔽，禁止尝试格式化、擦除或写入分区！',
  '  - 禁止修改 SELinux（setenforce 0）、禁止重刷系统、禁止未经授权的 reboot / fastboot / recovery 重启命令；',
  '  - [POLICY_BLOCKED] 表示触发了设备防砖保护策略，绝对不可执行，严禁试图寻找替代命令或利用脚本混淆绕过！',
  '  - 破坏性命令（如 rm -rf、dd、格式化、清应用数据等）会自动触发用户确认弹窗或通知栏，必须等待用户授权，切勿重复死循环调用；',
  '  - 屏幕操作节奏：手机界面动作后检查工具返回的新屏幕与结果；若仍需继续该手机界面任务而缺少有效观察，才用 screen；不为原生业务结果或开发任务补读屏，编号不得跨屏复用，切忌盲目连点；',
  '  - 与用户交流一律使用中文。',
].join('\n')

function approvalTitle(value) {
  if (typeof value !== 'string') return '';
  const text = value.replace(/[\u061c\u200e\u200f\u202a-\u202e\u2066-\u2069]/g, '')
    .replace(/[\s\u0000-\u001f\u007f-\u009f]+/g, ' ').trim();
  const chars = Array.from(text);
  return chars.length > 200 ? chars.slice(0, 200).join('') + '…' : text;
}

export function apply(ctx) {
  // Resolve per execution so parallel conversations and descendants never share a global source.
  ctx.inject(['shellEnv', 'subagents', 'sessionProjections'], sourceCtx => {
    sourceCtx.shellEnv.register({
      name: 'dsha-command-approval-source',
      variables: {
        DSH_APPROVAL_ROOT_SESSION_ID: { description: 'Main conversation ID for command approval routing.' },
        DSH_APPROVAL_CONVERSATION_TITLE: { description: 'Main conversation title for command approval display.' },
        DSH_APPROVAL_AGENT_TITLE: { description: 'Subagent task title for command approval display.' },
      },
      resolve({ agent }) {
        if (!agent) return {};
        try {
          const root = sourceCtx.subagents.rootOf(agent);
          const title = sourceCtx.sessionProjections.stateOf(root.session, 'title');
          const child = root !== agent;
          // A fork can inherit its parent's chat title; use the child's own task descriptor.
          const identity = child ? sourceCtx.sessionProjections.stateOf(agent.session, 'subagent').identity : undefined;
          return {
            DSH_APPROVAL_ROOT_SESSION_ID: root.session.header.id,
            DSH_APPROVAL_CONVERSATION_TITLE: approvalTitle(title) || '未命名对话',
            DSH_APPROVAL_AGENT_TITLE: child
              ? approvalTitle(identity && agent.session.isOwnSeq(identity.seq) ? identity.label : '') || '未命名子代理'
              : '',
          };
        } catch {
          // Missing runtime ownership must not misattribute a child command to itself.
          return {};
        }
      },
    });
  });
  let systemActive = false;
  const guided = new WeakSet();
  const inspected = new WeakSet();
  const isGuide = event => event?.type === 'user/message'
    && event.data?.source?.kind === 'plugin' && event.data.source.plugin === name;
  // 作用域注入不会阻塞极简配置的启动；标准配置只走系统提示，避免双份引导。
  ctx.inject(['systemPrompt'], promptCtx => {
    promptCtx.systemPrompt.section({ name: 'dsh:device-shell-guide', order: 150, text: PROMPT });
    promptCtx.effect(() => { systemActive = true; return () => { systemActive = false; }; });
  });
  ctx.on('session/event', (session, event) => { if (isGuide(event)) guided.add(session); });
  ctx.on('agent/pre-step', async ({ agent, step, signal }, next) => {
    const decision = await next();
    if (systemActive || decision.kind !== 'enter' || signal?.aborted || step !== 1) return decision;
    const session = agent?.session;
    if (!session || !Array.isArray(decision.messages) || decision.messages.length === 0) return decision;
    if (!inspected.has(session)) {
      inspected.add(session);
      // 恢复/分叉的历史也参与判重；不修改已有 V3 事件或消息身份。
      if (session.snapshotEvents?.().some(isGuide)) guided.add(session);
    }
    if (guided.has(session)) return decision;
    const guide = createUserMessage({
      content: [{ type: 'text', text: PROMPT }],
      source: { kind: 'plugin', plugin: name, form: 'snapshot', sections: [{ name, text: PROMPT }] },
    });
    // 后续插件仍可能拒绝步骤，因此仅在 session/event 确认写入后标记已注入。
    return { ...decision, messages: [guide, ...decision.messages] };
  }, { prepend: true });
}
