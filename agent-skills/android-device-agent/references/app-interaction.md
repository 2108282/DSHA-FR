# 第三方应用高鲁棒交互模式（App Interaction Playbook）

在第三方 App 缺少直接系统 Intent 时，使用以下标准工作流进行 GUI 交互：

## 1. 启动并等待进入前台
```python
agent.launch_app("com.tencent.mm")
if not agent.wait_for_package("com.tencent.mm", timeout=8):
    print("应用未能按时进入前台，终止操作")
```

## 2. 动态弹窗拦截（Permission / Update / Notice）
许多 App 在启动时会有“青少年模式提示”、“版本更新”或“权限申请”弹窗：
- 在进入主流程前，先 Dump 一次当前界面；
- 检查是否存在关键词：“我知道了”、“取消”、“暂不更新”、“稍后”、“同意并继续”；
- 优先点击关闭/跳过弹窗，再执行主要业务流程。

## 3. 列表滚动查找（Scroll & Search Pattern）
当目标按钮不在当前可见视口时：
```python
max_scrolls = 5
found = False

for _ in range(max_scrolls):
    elements = agent.find_elements(text="目标项")
    if elements:
        agent.tap(text="目标项")
        found = True
        break
    # 没找到则向下滑动一屏
    agent.scroll(direction="down")
    time.sleep(0.5)

if not found:
    print("滚动 5 次后仍未找到目标项")
```

## 4. 键盘与输入框聚焦原则
- 在向输入框键入文本前，务必先通过 `agent.tap(text="输入框提示文字")` 或 `agent.tap(x=..., y=...)` 激活软键盘和焦点；
- 聚焦后等待 300ms 让输入法和光标就绪；
- 紧接着调用 `agent.smart_input(text, paste=True)` 进行输入；
- 输入完成后若需要搜索或发送，调用 `agent.press_key("enter")` 或点击界面上的“搜索/发送”按钮。
