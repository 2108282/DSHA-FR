---
name: android-device-agent
description: "Android 手机自动化操控与设备交互专家：深度借鉴 Eta 设计哲学，提供 Direct First（直达优先）、Observe-Act-Verify（观察-行动-验证闭环）、智能文本输入（剪贴板粘贴）及双轨驱动（无障碍+Root）等全套高级操控逻辑。当用户需要操控手机、打开应用、点击界面、输入文字、查询或修改系统设置、设置闹钟/计时器、自动化任务时使用。"
whenToUse: "当任务涉及自动化操作手机、点击屏幕、输入内容、截屏看屏、获取设备状态、直接调用系统接口（闹钟/音量/应用启停/Settings修改）等场景时调用。"
---

# Android Device Agent（手机自动化操控全套手册）

本手册深度吸收并实现了 **Eta（面向 Android 的系统级 AI 助手）** 的完整操控逻辑体系，结合宿主原生 Root 特权与 Local Bridge（127.0.0.1:3095），为 AI Agent 提供高鲁棒性、零失误的实体手机自动化操控能力。

---

## 一、 核心设计

### 1. Direct First（直达优先，绝不滥用 GUI 逐步点击）
- **原则**：只要能通过 Android Intent（`am start`）、系统设置（`settings`）、包管理（`pm`）或系统命令行直接完成的操作，**坚决不要用 GUI 逐层寻找和点击**！
- **对比**：
  - ❌ 劣质方案：点击进入“设置” -> 向上滑动 -> 找到“关于手机” -> 寻找电池 -> 查看电量。
  - ✅ Eta 直达方案：直接读取 `/app/device` 或 `dumpsys battery`。
  - ❌ 劣质方案：打开时钟 App -> 点击“+”号 -> 滚动数字选择 07:30 -> 点击保存。
  - ✅ Eta 直达方案：`android-agent alarm 7 30 "起床"`（直接通过 `am start -a android.intent.action.SET_ALARM` 免界面注入）。

### 2. Observe -> Act -> Verify（观察-行动-验证闭环）
- **绝不盲目连点**：每次点按或输入之后，界面结构都会发生变化。必须重新 Dump UI 树或确认焦点后再执行下一步。
- **验证机制（Replace 固定 sleep）**：
  - 点击应用图标后：使用 `android-agent wait-pkg <pkg>` 确认应用真正进入前台。
  - 点击保存/确认按钮后：使用 `android-agent wait-text <文字>` 确认结果已经呈现或弹窗关闭。

### 3. Smart Input（智能文本输入策略）
- **常见陷阱**：直接调用 `input text` 往往会遇到中文乱码、丢失字符、特殊符号截断或输入法预测栏粘滞等问题。
- **Eta 最优方案**：
  1. 将文字写入系统剪贴板：`/app/clip?text=...`（或 `android-agent input "内容" --paste`）。
  2. 触发系统级粘贴动作：发送按键 `KEYCODE_PASTE` (279) 或调用无障碍粘贴。
  3. 对于需要清空已有内容的输入框：先聚焦，执行全选删除后再粘贴。

### 4. Dual-Engine（双轨驱动与自动回退）
- **无障碍通道（Bridge 127.0.0.1:3095）**：按文本点击（抗分辨率缩放）、无障碍读屏。
- **特权通道（Root / nsenter）**：`screencap -p` 极速免弹窗截屏、`input tap/swipe/keyevent` 毫秒级手势、`dumpsys` 前台检测。
- 遇阻时平滑降级，确保任务不被单点阻塞中断。

---

## 二、 自动化辅助工具库：`android-agent` CLI

系统已内置全局可执行命令 `android-agent`（对应脚本 `/root/.dsh/scripts/android_agent.py`），提供一键式调用：

### 1. 屏幕观察与状态获取
```bash
# 获取设备状态与当前前台应用包名/Activity
android-agent device

# 读取当前屏幕 UI 树节点 (包含 index, 文字, 中心坐标, 点击属性)
android-agent dump

# 截取屏幕 (保存到 Download/DSHA/，返回完整路径)
android-agent screenshot
```

### 2. 界面交互手势
```bash
# 1. 优先按文字点击 (抗界面偏移)
android-agent tap --text "确认"

# 2. 按屏幕坐标点击
android-agent tap --x 540 --y 1200

# 3. 滑动 (从 x1,y1 到 x2,y2，耗时 ms)
android-agent swipe 500 1600 500 400 --ms 300

# 4. 滚动屏幕 (up / down / left / right)
android-agent scroll down

# 5. 系统按键 (back / home / recents / enter / lock)
android-agent key back
android-agent key home
```

### 3. 智能文本输入
```bash
# 智能粘贴输入 (天然支持中文、Emoji、超长文本)
android-agent input "你好，这是一条自动化输入的中文！"

# 清空已有文本后输入
android-agent input "替换后的内容" --clear
```

### 4. 等待与校验
```bash
# 等待界面上出现特定文本 (最多等待 10 秒)
android-agent wait-text "支付成功" --timeout 10

# 等待特定应用进入前台
android-agent wait-pkg "com.tencent.mm" --timeout 8
```

### 5. 系统直达指令
```bash
# 启动应用
android-agent launch com.tencent.mm

# 设置闹钟 (时 分 [标签])
android-agent alarm 7 30 "起床"

# 设置计时器 (秒数 [标签])
android-agent timer 300 "煮泡面"
```

---

## 三、 Python 模块集成调用

在 Python 脚本或自动化程序中，可直接导入使用：

```python
from android_agent import AndroidAgent

agent = AndroidAgent()

# 1. 获取设备与前台状态
info = agent.get_device_info()
focus = agent.get_current_focus()
print(f"当前在前台应用: {focus['package']}")

# 2. 启动应用并等待就绪
agent.launch_app("com.tencent.mm")
if agent.wait_for_package("com.tencent.mm", timeout=8):
    print("微信启动成功！")

# 3. 查找元素并点击
elements = agent.find_elements(text="通信录")
if elements:
    agent.tap(text="通信录")

# 4. 智能输入
agent.smart_input("搜索联系人", paste=True)
```

---

## 四、 安全守卫与红线约束

1. **防砖红线**：
   - 严禁对 `/dev/block/` 块设备执行格式化、dd、写入。
   - 严禁执行 `setenforce 0` 或修改 SELinux。
   - 严禁未经用户明确授权执行 `reboot`、`fastboot` 或刷机命令。
2. **敏感个人数据保护**：
   - 严禁将大批量未经脱敏的通讯录、短信全文、私有照片缓存写入聊天记录或回传远端模型。
   - 读取短信验证码时，严格使用正则提取其中的 4-6 位纯数字验证码，不泄露完整通信正文。
3. **高风险动作确认**：
   - 涉及删除账户、清空应用数据（`pm clear`）、转账支付等不可逆操作，必须调用 `/app/ask` 明确向用户弹出确认选项，等待授权后方可执行。

---

## 五、 参考附录

- `references/direct-intents.md`：常用系统 Intent 与底层指令速查。
- `references/app-interaction.md`：第三方 App（微信、电商、地图等）的高鲁棒交互模式。
