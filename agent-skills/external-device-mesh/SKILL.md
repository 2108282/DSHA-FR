---
name: external-device-mesh
description: 跨设备自动化互联中枢技能：当用户需要操控局域网内的其他 Android 设备（如平板、电视盒子、备用机）或远程 Linux/Windows 主机（执行命令、运维排障、跨机传输文件、查状态）时使用。
---

# 外部设备网络互联与跨机操控规范 (External Device Mesh)

本系统配备了物理级双向设备互联底座。所有已连接的局域网 Android 设备与远程 SSH 主机均由系统实时维护并落盘在容器内。

## 一、 设备信息与真实物理存储源

1. **已连接 Android 设备清单 (ADB)**：
   - 物理路径：`/root/.dsh/connected_devices.json`
   - 内容结构：JSON 数组，包含所有通过有线 OTG 或局域网无线连接的安卓设备地址与状态。
   - 调度原则：跨机操控时，直接读取该文件获取可用目标，优先按设备备注或型号匹配。

2. **远程 Linux / Windows 主机清单 (SSH)**：
   - 物理路径：`/root/.ssh/config`
   - 伴生密码托管路径：`/root/.ssh/passwords.json`（600 权限）
   - 内容结构：标准 OpenSSH 配置文件，每个远程节点使用 `Host <别名>` 声明，并已全局注入免确认策略。
   - 调度原则：直接使用配置中的别名发起连接（例如 `ssh 阿里云 "df -h"`），无需死记 IP 与端口。

## 二、 自动化跨机调用最佳实践（防挂死核心）

1. **调用远程 SSH 主机**：
   - 严禁触发交互式密码输入或指纹确认，统一由系统公钥凭据（`~/.ssh/id_ed25519`）或自动注入的 `sshpass` 保障；
   - 推荐调用范式：
     ```bash
     # 免密主机
     ssh -o StrictHostKeyChecking=no <Host别名> "<命令>"

     # 跨机传输文件
     scp -o StrictHostKeyChecking=no <本地文件> <Host别名>:<远程目录>
     ```

2. **调用远程 Android 设备 (ADB)**：
   - 多机场景下必须强制携带 `-s <设备地址>` 参数；
   - 唤醒/点按：
     ```bash
     adb -s <目标地址> shell input keyevent 26
     adb -s <目标地址> shell input tap 500 1000
     ```
   - 远程截图：
     ```bash
     adb -s <目标地址> exec-out screencap -p > /tmp/remote_screen.png
     ```
   - 远程装包与传文件：
     ```bash
     adb -s <目标地址> install <本地APK路径>
     adb -s <目标地址> push <本地文件> /sdcard/Download/
     ```
