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
  '■ 三条核心通道，按场景调用：',
  '  1) 容器开发通道：Ubuntu 工作区内的编译、代码、脚本与文件操作直接使用普通 bash 和文件工具（原生 Root uid=0）。',
  '  2) 原生手机操控 MCP (mcp__phone__*)：操作手机屏幕、点击、输入、长列表收集 (collect)、后台虚拟屏 (background:true)、系统意图 (intent: 闹钟/计时器/日程/导航/分享/拨号/短信) 与人机交接 (handoff) 首选直接调用 mcp__phone__* 工具！',
  '  3) Android 硬件与特权补充通道：手电筒、震动、传感器等走 127.0.0.1:3095/app/*；容器终端内已直通 am、pm、cmd、input、screencap、dumpsys 命令。',
  '',
  '■ 完整端点清单（读屏 / 点按 / 输入 / 截屏 / 通知 / 剪贴板 / 传感器 / 导出文件 …）：',
  '  T=$(cat /root/.dsh/.bridge_token)',
  '  curl -s "http://127.0.0.1:3095/app/help?token=$T"',
  '  → 需要具体端点参数时查一次，最小常用清单：',
  '    设备信息 /app/device · 应用列表 /app/apps?q= · 启动应用 /app/launch?pkg=',
  '    读屏 /app/ui/dump · 文字点按 /app/ui/tap?text= · 坐标点按 /app/ui/tap?x=&y= · 输入 /app/ui/input?text=',
  '    滑动 /app/ui/swipe · 截屏 /app/ui/screenshot · 按键 /app/ui/key',
  '    通知 /app/notify?title=&text= · 提问 /app/ask?q=&options=a|b · Toast /app/toast?text=',
  '    剪贴板 /app/clip（写入加 ?text=） · 分享 /app/share?text= · 开链接 /app/open?url=',
  '    震动 /app/vibrate?ms= · 读外部存储 /app/readfile?path= · 手电 /app/torch?on=1',
  '',
  '■ 安全守卫与硬约束（防砖安全，严格遵守）：',
  '  - 【DSH本体保活·绝对防自毁】：你的意识与运行环境完全依托于后台运行的 DeepSeek Harness (DSH) Node.js 进程（监听 3080 端口，PID 见 /root/.dsh/dsh.pid）以及 Android 原生桥接服务（3095 端口）。严禁在排障中执行 killall node、pkill -f node/dsh、fuser -k 3080/tcp、kill <DSH_PID> 等任何杀死自身进程的操作！杀死该进程会导致当前 Agent 立即崩溃死亡。',
  '  - 【移动端禁止全盘 find 扫盘】：Android 宿主 /sdcard 基于 FUSE 用户态文件系统且存储量大（常达数百 GB），递归遍历根目录(/)或 /sdcard 会导致 CPU 瞬间满载、整机发热、掉电并造成系统 UI 严重卡顿。严禁执行 find / 或 find /sdcard 扫全盘！定位文件必须收敛范围（如工作区、/data/adb/dsha/、/sdcard/Download/DSHA/），优先使用内置 glob 与 grep 工具，或加 -maxdepth 限制。',
  '  - 【对外执行无界（SSH / ADB 远端）】：本体保活与 FUSE 功耗限制仅针对本机环境生效。当通过 ssh 远程操作外部服务器、或通过 adb -s 操控局域网外部设备时，命令由对端远程系统独立解析执行，不受本机规则限制。',
  '  - 物理安全第一：物理块设备分区（/dev/block）受内核隔离屏蔽，禁止尝试格式化、擦除或写入分区！',
  '  - 禁止修改 SELinux（setenforce 0）、禁止重刷系统、禁止未经授权的 reboot / fastboot / recovery 重启命令；',
  '  - [POLICY_BLOCKED] 表示触发了设备防砖保护策略，绝对不可执行，严禁试图寻找替代命令或利用脚本混淆绕过！',
  '  - 破坏性命令（如 rm -rf、dd、格式化、清应用数据等）会自动触发用户确认弹窗或通知栏，必须等待用户授权，切勿重复死循环调用；',
  '  - 屏幕操作节奏：每次点按或输入之后先 /app/ui/dump 再决定下一步，界面可能已变化，切忌盲目连点；',
  '  - 与用户交流一律使用中文。',
].join('\n')

export function apply(ctx) {
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
