#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Android Agent 核心自动化工具库 (借鉴 Eta 全套操控逻辑)
双轨融合架构：
1. Android 框架接口 (127.0.0.1:3095 /app/* 无障碍与系统服务)
2. 宿主特权通道 (Root uid=0 / nsenter / am / pm / input / screencap / dumpsys)
具备 Direct 优先、UI 树解析、等待校验与智能文本输入机制。
"""

import os
import sys
import time
import json
import re
import argparse
import subprocess
import urllib.request
import urllib.parse
from typing import Optional, List, Dict, Any, Tuple

BRIDGE_URL = "http://127.0.0.1:3095"
TOKEN_FILE = "/root/.dsh/.bridge_token"


class AndroidAgent:
    def __init__(self, bridge_url: str = BRIDGE_URL, token_file: str = TOKEN_FILE):
        self.bridge_url = bridge_url
        self.token = self._read_token(token_file)

    def _read_token(self, path: str) -> str:
        if os.path.exists(path):
            try:
                with open(path, "r", encoding="utf-8") as f:
                    return f.read().strip()
            except Exception:
                pass
        return ""

    def _call_bridge(self, endpoint: str, params: Optional[Dict[str, Any]] = None, timeout: float = 3.0) -> Tuple[bool, Any]:
        """安全调用 3095 桥接口，带超时保护"""
        if params is None:
            params = {}
        if self.token:
            params["token"] = self.token
        
        url = f"{self.bridge_url}{endpoint}"
        query = urllib.parse.urlencode(params)
        full_url = f"{url}?{query}" if query else url
        
        try:
            req = urllib.request.Request(full_url, headers={"User-Agent": "AndroidAgent/1.0"})
            with urllib.request.urlopen(req, timeout=timeout) as response:
                raw = response.read().decode("utf-8")
                try:
                    data = json.loads(raw)
                    res = data.get("result", data)
                    # 检查是否包含拒绝信息
                    if isinstance(res, str) and ("[ERR]" in res or "拒绝" in res):
                        return False, res
                    return True, res
                except json.JSONDecodeError:
                    return True, raw
        except Exception as e:
            return False, str(e)

    def _run_cmd(self, cmd: List[str], timeout: float = 10.0) -> Tuple[int, str, str]:
        """执行本地命令（直通宿主特权）"""
        try:
            res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=timeout)
            return res.returncode, res.stdout.strip(), res.stderr.strip()
        except Exception as e:
            return -1, "", str(e)

    def _run_nsenter(self, cmd_args: List[str], timeout: float = 10.0) -> Tuple[int, str, str]:
        """通过 nsenter 进入宿主 init 命名空间执行底层命令"""
        full_cmd = ["/usr/bin/nsenter", "-t", "1", "-m"] + cmd_args
        return self._run_cmd(full_cmd, timeout=timeout)

    # ==========================
    # 1. 观察与感知 (Observe)
    # ==========================

    def get_device_info(self) -> Dict[str, Any]:
        """读取设备基础状态"""
        ok, res = self._call_bridge("/app/device")
        if ok and isinstance(res, str):
            info = {}
            for line in res.splitlines():
                if "=" in line:
                    k, v = line.split("=", 1)
                    info[k.strip()] = v.strip()
            return info
        return {"error": str(res)}

    def get_current_focus(self) -> Dict[str, str]:
        """获取当前获得焦点的窗口与前台包名"""
        code, out, _ = self._run_cmd(["dumpsys", "window"])
        pkg, activity = "", ""
        if code == 0:
            for line in out.splitlines():
                if "mCurrentFocus" in line or "mFocusedApp" in line:
                    match = re.search(r"([a-zA-Z0-9._]+)/([a-zA-Z0-9._]+)", line)
                    if match:
                        pkg, activity = match.group(1), match.group(2)
                        break
        return {"package": pkg, "activity": activity}

    def dump_ui(self) -> List[Dict[str, Any]]:
        """
        读取 UI 节点树。
        解析格式：[序号] "文字" 可点击 中心=(x,y) 区域=l,t,r,b
        """
        ok, res = self._call_bridge("/app/ui/dump", timeout=4.0)
        elements = []
        if ok and isinstance(res, str):
            lines = res.splitlines()
            for line in lines:
                line = line.strip()
                if not line.startswith("["):
                    continue
                # 解析节点
                idx_match = re.match(r"\[(\d+)\]\s*(\".*?\")?", line)
                if not idx_match:
                    continue
                idx = int(idx_match.group(1))
                text = idx_match.group(2).strip('"') if idx_match.group(2) else ""
                
                center_match = re.search(r"中心=\((\d+),\s*(\d+)\)", line)
                center = (int(center_match.group(1)), int(center_match.group(2))) if center_match else None
                
                area_match = re.search(r"区域=(\d+),(\d+),(\d+),(\d+)", line)
                bounds = (int(area_match.group(1)), int(area_match.group(2)), int(area_match.group(3)), int(area_match.group(4))) if area_match else None
                
                clickable = "可点击" in line
                elements.append({
                    "index": idx,
                    "text": text,
                    "center": center,
                    "bounds": bounds,
                    "clickable": clickable,
                    "raw": line
                })
        return elements

    def find_elements(self, text: Optional[str] = None, clickable_only: bool = False) -> List[Dict[str, Any]]:
        """在当前 UI 树中查找匹配的元素"""
        elements = self.dump_ui()
        matched = []
        for el in elements:
            if clickable_only and not el["clickable"]:
                continue
            if text:
                if text.lower() in el["text"].lower():
                    matched.append(el)
            else:
                matched.append(el)
        return matched

    def screenshot(self, save_path: Optional[str] = None) -> str:
        """
        截屏。双轨策略：优先 Root 特权 screencap (零授权弹窗，极速)，失败回退 Bridge。
        """
        if not save_path:
            save_path = f"/sdcard/Download/DSHA/screen_{int(time.time())}.png"
        
        os.makedirs(os.path.dirname(save_path), exist_ok=True)
        code, _, err = self._run_cmd(["screencap", "-p", save_path])
        if code == 0 and os.path.exists(save_path) and os.path.getsize(save_path) > 0:
            return save_path
        
        # 回退 Bridge 截屏
        ok, res = self._call_bridge("/app/ui/screenshot", timeout=5.0)
        if ok and isinstance(res, str) and os.path.exists(res):
            return res
        return ""

    # ==========================
    # 2. 交互动作 (Act)
    # ==========================

    def tap(self, text: Optional[str] = None, x: Optional[int] = None, y: Optional[int] = None) -> bool:
        """
        点击。策略：
        1. 若给出 text，优先通过 Bridge 文字点按（抗分辨率与滚动影响）；
        2. 若 Bridge 失败或只给出坐标，回退通过 input tap (Root 级极速响应)。
        """
        if text:
            ok, res = self._call_bridge("/app/ui/tap", {"text": text}, timeout=3.0)
            if ok:
                return True
            # 文字点按如果未通过 Bridge 触发，尝试 dump 后坐标点按
            els = self.find_elements(text=text)
            if els and els[0]["center"]:
                x, y = els[0]["center"]
        
        if x is not None and y is not None:
            code, _, _ = self._run_cmd(["input", "tap", str(x), str(y)])
            return code == 0
        return False

    def long_press(self, x: int, y: int, duration_ms: int = 800) -> bool:
        """长按坐标 (通过 input swipe 原地模拟)"""
        code, _, _ = self._run_cmd(["input", "swipe", str(x), str(y), str(x), str(y), str(duration_ms)])
        return code == 0

    def swipe(self, x1: int, y1: int, x2: int, y2: int, duration_ms: int = 300) -> bool:
        """滑动手势"""
        code, _, _ = self._run_cmd(["input", "swipe", str(x1), str(y1), str(x2), str(y2), str(duration_ms)])
        return code == 0

    def scroll(self, direction: str = "down", distance: int = 800) -> bool:
        """按浏览方向滚动：down = 向下滑动以展示下方内容"""
        # 默认取屏幕常见中心区域 (按 1080x2400 标准居中)
        cx = 540
        cy = 1200
        half = distance // 2
        if direction == "down":
            return self.swipe(cx, cy + half, cx, cy - half, 300)
        elif direction == "up":
            return self.swipe(cx, cy - half, cx, cy + half, 300)
        elif direction == "right":
            return self.swipe(cx - half, cy, cx + half, cy, 300)
        elif direction == "left":
            return self.swipe(cx + half, cy, cx - half, cy, 300)
        return False

    def press_key(self, key_name: str) -> bool:
        """按键操作 (back, home, recents, enter, lock, volume_up, volume_down 等)"""
        key_map = {
            "back": "4",
            "home": "3",
            "recents": "187",
            "enter": "66",
            "delete": "67",
            "tab": "61",
            "space": "62",
            "volume_up": "24",
            "volume_down": "25",
            "media_play_pause": "85",
            "paste": "279",
        }
        name_lower = key_name.lower()
        if name_lower in key_map:
            code, _, _ = self._run_cmd(["input", "keyevent", key_map[name_lower]])
            return code == 0
        else:
            # 尝试走 bridge 按键
            ok, _ = self._call_bridge("/app/ui/key", {"name": name_lower})
            return ok

    def set_clipboard(self, text: str) -> bool:
        """写入系统剪贴板"""
        ok, _ = self._call_bridge("/app/clip", {"text": text}, timeout=3.0)
        return ok

    def smart_input(self, text: str, paste: bool = True, clear_first: bool = False) -> bool:
        """
        智能文本输入策略 (借鉴 Eta)：
        对于中文、Emoji、长文本，原生 `input text` 容易乱码或丢字。
        最稳策略：写入系统剪贴板 -> 发送 KEYEVENT_PASTE (279) 粘贴！
        """
        if clear_first:
            # 全选删除 (Ctrl+A -> Backspace)
            self._run_cmd(["input", "keyevent", "--longpress", "29"])  # KEYCODE_A
            time.sleep(0.1)
            self.press_key("delete")
            time.sleep(0.1)

        # 检查是否包含非 ASCII 字符或要求 paste
        has_non_ascii = any(ord(c) > 127 for c in text)
        if paste or has_non_ascii:
            if self.set_clipboard(text):
                time.sleep(0.2)
                return self.press_key("paste")
        
        # 回退到 Bridge 输入或 input text
        ok, _ = self._call_bridge("/app/ui/input", {"text": text}, timeout=3.0)
        if ok:
            return True
        safe_text = text.replace(" ", "%s").replace("'", "\\'")
        code, _, _ = self._run_cmd(["input", "text", safe_text])
        return code == 0

    # ==========================
    # 3. 等待与校验 (Verify)
    # ==========================

    def wait_for_text(self, text: str, timeout: float = 10.0, interval: float = 0.8) -> bool:
        """轮询等待屏幕出现指定文本 (取代固定 sleep)"""
        start = time.time()
        while time.time() - start < timeout:
            elements = self.find_elements(text=text)
            if elements:
                return True
            time.sleep(interval)
        return False

    def wait_for_package(self, package_name: str, timeout: float = 10.0, interval: float = 0.5) -> bool:
        """轮询等待指定应用进入前台"""
        start = time.time()
        while time.time() - start < timeout:
            focus = self.get_current_focus()
            if package_name.lower() in focus.get("package", "").lower():
                return True
            time.sleep(interval)
        return False

    # ==========================
    # 4. 直达与特权控制 (Direct First)
    # ==========================

    def launch_app(self, package_name: str) -> bool:
        """启动应用。双轨：优先 /app/launch，回退 am start"""
        ok, _ = self._call_bridge("/app/launch", {"pkg": package_name})
        if ok:
            return True
        code, _, _ = self._run_cmd(["am", "start", "-p", package_name])
        return code == 0

    def open_uri(self, uri: str) -> bool:
        """用系统默认方式打开 URI / 链接"""
        ok, _ = self._call_bridge("/app/open", {"url": uri})
        if ok:
            return True
        code, _, _ = self._run_cmd(["am", "start", "-a", "android.intent.action.VIEW", "-d", uri])
        return code == 0

    def set_alarm(self, hour: int, minute: int, label: str = "闹钟") -> bool:
        """免界面直接创建系统闹钟"""
        cmd = [
            "am", "start", "-a", "android.intent.action.SET_ALARM",
            "--ei", "android.intent.extra.alarm.HOUR", str(hour),
            "--ei", "android.intent.extra.alarm.MINUTES", str(minute),
            "--es", "android.intent.extra.alarm.MESSAGE", label,
            "--ez", "android.intent.extra.alarm.SKIP_UI", "true"
        ]
        code, _, _ = self._run_cmd(cmd)
        return code == 0

    def set_timer(self, seconds: int, label: str = "计时器") -> bool:
        """免界面直接创建系统计时器"""
        cmd = [
            "am", "start", "-a", "android.intent.action.SET_TIMER",
            "--ei", "android.intent.extra.alarm.LENGTH", str(seconds),
            "--es", "android.intent.extra.alarm.MESSAGE", label,
            "--ez", "android.intent.extra.alarm.SKIP_UI", "true"
        ]
        code, _, _ = self._run_cmd(cmd)
        return code == 0

    def set_volume(self, stream: str, percent: int) -> bool:
        """设置音量 (media / ring / alarm)"""
        # stream index 映射：media=3, ring=2, alarm=4
        stream_map = {"media": "3", "ring": "2", "alarm": "4"}
        idx = stream_map.get(stream.lower(), "3")
        # Android volume 范围一般在 0-15 或 0-25，按百分比折算
        val = int(percent * 15 / 100)
        code, _, _ = self._run_nsenter(["cmd", "media_session", "volume", "--stream", idx, "--set", str(val)])
        return code == 0

    def notify(self, title: str, text: str) -> bool:
        """发送系统通知栏消息"""
        ok, _ = self._call_bridge("/app/notify", {"title": title, "text": text})
        return ok

    def toast(self, text: str) -> bool:
        """弹出 Toast 消息"""
        ok, _ = self._call_bridge("/app/toast", {"text": text})
        return ok


def main():
    parser = argparse.ArgumentParser(description="Android Agent CLI (Eta-compatible Control Tool)")
    subparsers = parser.add_subparsers(dest="subcommand", help="子命令")

    # device
    subparsers.add_parser("device", help="获取设备状态与前台应用")
    
    # dump
    subparsers.add_parser("dump", help="读取屏幕 UI 节点树")

    # tap
    tap_parser = subparsers.add_parser("tap", help="点击文本或坐标")
    tap_parser.add_argument("--text", type=str, help="按文本点击")
    tap_parser.add_argument("--x", type=int, help="X 坐标")
    tap_parser.add_argument("--y", type=int, help="Y 坐标")

    # input
    in_parser = subparsers.add_parser("input", help="输入文本")
    in_parser.add_argument("text", type=str, help="要输入的文字")
    in_parser.add_argument("--paste", action="store_true", default=True, help="使用剪贴板粘贴模式 (默认开启)")
    in_parser.add_argument("--clear", action="store_true", help="输入前先清空输入框")

    # swipe / scroll
    swipe_parser = subparsers.add_parser("swipe", help="滑动")
    swipe_parser.add_argument("x1", type=int)
    swipe_parser.add_argument("y1", type=int)
    swipe_parser.add_argument("x2", type=int)
    swipe_parser.add_argument("y2", type=int)
    swipe_parser.add_argument("--ms", type=int, default=300)

    scroll_parser = subparsers.add_parser("scroll", help="滚动屏幕 (up/down/left/right)")
    scroll_parser.add_argument("direction", choices=["up", "down", "left", "right"], default="down", nargs="?")

    # key
    key_parser = subparsers.add_parser("key", help="按键 (back/home/recents/enter/lock/paste)")
    key_parser.add_argument("name", type=str)

    # screenshot
    ss_parser = subparsers.add_parser("screenshot", help="截取屏幕")
    ss_parser.add_argument("--out", type=str, help="保存路径")

    # wait
    wt_parser = subparsers.add_parser("wait-text", help="等待界面出现文字")
    wt_parser.add_argument("text", type=str)
    wt_parser.add_argument("--timeout", type=float, default=10.0)

    wp_parser = subparsers.add_parser("wait-pkg", help="等待应用进入前台")
    wp_parser.add_argument("package", type=str)
    wp_parser.add_argument("--timeout", type=float, default=10.0)

    # direct actions
    launch_parser = subparsers.add_parser("launch", help="启动应用")
    launch_parser.add_argument("package", type=str)

    alarm_parser = subparsers.add_parser("alarm", help="设置闹钟 (小时 分钟 [标签])")
    alarm_parser.add_argument("hour", type=int)
    alarm_parser.add_argument("minute", type=int)
    alarm_parser.add_argument("label", type=str, default="闹钟", nargs="?")

    timer_parser = subparsers.add_parser("timer", help="设置计时器 (秒数 [标签])")
    timer_parser.add_argument("seconds", type=int)
    timer_parser.add_argument("label", type=str, default="计时器", nargs="?")

    args = parser.parse_args()
    agent = AndroidAgent()

    if args.subcommand == "device":
        info = agent.get_device_info()
        focus = agent.get_current_focus()
        print("=== 设备状态 ===")
        for k, v in info.items():
            print(f"{k}: {v}")
        print(f"foreground_package: {focus.get('package')}")
        print(f"foreground_activity: {focus.get('activity')}")

    elif args.subcommand == "dump":
        nodes = agent.dump_ui()
        print(f"找到 {len(nodes)} 个 UI 节点:")
        for n in nodes[:50]:
            print(n["raw"])
        if len(nodes) > 50:
            print(f"... 其余 {len(nodes) - 50} 个节点已省略")

    elif args.subcommand == "tap":
        res = agent.tap(text=args.text, x=args.x, y=args.y)
        print(f"点击结果: {'成功' if res else '失败'}")

    elif args.subcommand == "input":
        res = agent.smart_input(args.text, paste=args.paste, clear_first=args.clear)
        print(f"输入结果: {'成功' if res else '失败'}")

    elif args.subcommand == "swipe":
        res = agent.swipe(args.x1, args.y1, args.x2, args.y2, args.ms)
        print(f"滑动结果: {'成功' if res else '失败'}")

    elif args.subcommand == "scroll":
        res = agent.scroll(args.direction)
        print(f"滚动结果: {'成功' if res else '失败'}")

    elif args.subcommand == "key":
        res = agent.press_key(args.name)
        print(f"按键结果: {'成功' if res else '失败'}")

    elif args.subcommand == "screenshot":
        path = agent.screenshot(args.out)
        print(f"截图路径: {path}")

    elif args.subcommand == "wait-text":
        res = agent.wait_for_text(args.text, timeout=args.timeout)
        print(f"等待文字 [{args.text}] 结果: {'已出现' if res else '超时未出现'}")

    elif args.subcommand == "wait-pkg":
        res = agent.wait_for_package(args.package, timeout=args.timeout)
        print(f"等待应用 [{args.package}] 结果: {'已就绪' if res else '超时未就绪'}")

    elif args.subcommand == "launch":
        res = agent.launch_app(args.package)
        print(f"启动应用结果: {'已发起' if res else '失败'}")

    elif args.subcommand == "alarm":
        res = agent.set_alarm(args.hour, args.minute, args.label)
        print(f"设置闹钟结果: {'已创建' if res else '失败'}")

    elif args.subcommand == "timer":
        res = agent.set_timer(args.seconds, args.label)
        print(f"设置计时器结果: {'已创建' if res else '失败'}")

    else:
        parser.print_help()


if __name__ == "__main__":
    main()
