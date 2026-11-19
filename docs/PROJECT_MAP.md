# DSHA Native 后端项目地图

本文件对应 `dsh-magisk` 分支的 KernelSU / Magisk 原生 chroot 后端。安装说明见 [README](../README.md)，构建与补丁约定见 [BUILD](../BUILD.md)。Android 前端位于独立的 `magisk-apk` 分支，不属于本后端工作树。

## 一、运行边界

- Android 前端 `com.dsha.fr` 调用宿主控制脚本，承载 DSH WebView，并提供手机 MCP 与独立设备桥。
- 宿主控制脚本安装在 `/data/adb/dsha/scripts/`，管理 `/data/adb/dsha/rootfs/` 内的原生 Ubuntu ARM64 环境。
- DSH Web 默认监听 `127.0.0.1:3080`；独立设备与运行时桥使用 `127.0.0.1:3095`。
- 本机电话、短信、日历、读屏和点击等任务走已安装的手机 MCP 工具。3095 保留独立设备能力、产物导出和既有插件运行时职责，不作为手机 MCP 失败后的绕行通道。
- 外部设备与 SSH 主机操作使用 [external-device-mesh](../agent-skills/external-device-mesh/SKILL.md)，明确目标，避免混同本机与远端。

## 二、本地仓库结构

```text
dsh-magisk/
├── .github/workflows/magisk-module-build.yml  # Full / Lite 构建
├── magisk-module/
│   ├── META-INF/                            # 模块安装入口
│   ├── customize.sh                         # Full 安装器
│   ├── customize.lite.sh                    # Lite 两步选择安装器
│   ├── scripts/                             # 七个基础运行时控制脚本
│   └── patches/                             # Lite 现场迁移补丁
├── scripts/                                 # 控制脚本镜像、本地打包与测试
├── patches/                                 # 现场迁移补丁镜像
├── rootfs-overlay/                          # Full / Lite 共用容器文件
│   └── root/
│       ├── .dsh/                            # 注册、自愈、生命周期与数据迁移
│       ├── dsh-bin/                         # 命令守卫包装器
│       ├── dsh-mobile-lite/                 # 移动端轻量界面
│       ├── dsh-quickchat-gesture/            # QuickChat 手势插件
│       └── dsha-device-shell-guide/         # 设备通道与安全提示插件
├── tools/
│   ├── dynamic-rootfs-merge.sh              # 离线合成底包
│   └── export-rootfs.sh                     # 实机底包导出工具
└── docs/                                    # 当前后端说明与有效迁移说明
```

容器文件放入对应的 overlay 路径；额外现场动作放入模块 patches，并同步根 patches。构建优先使用模块补丁目录，仅在没有脚本时回退根目录，不合并两者。

## 三、设备部署路径

| 项目 | Android 宿主路径 | 容器内路径或说明 |
|---|---|---|
| 根文件系统 | `/data/adb/dsha/rootfs/` | `/`，uid=0 的原生 Linux 环境 |
| 控制脚本 | `/data/adb/dsha/scripts/` | 在 Android 宿主调用 |
| 核心 PID | `/data/adb/dsha/run/dsh.pid` | 容器侧记录为 `/root/.dsh/dsh.pid` |
| Web 日志 | `/data/adb/dsha/run/dsh-web.log`（软链） | `/root/dsh-web.log`（实际文件），双端访问同一份日志 |
| 设备桥凭据 | `/data/adb/dsha/rootfs/root/.dsh/.bridge_token` | `/root/.dsh/.bridge_token`；不得提交、分享或发给网站 |
| Web profile | `/data/adb/dsha/rootfs/root/.dsh/profiles/web/` | `/root/.dsh/profiles/web/` |
| 命令包装器 | `/data/adb/dsha/rootfs/root/dsh-bin/` | `/root/dsh-bin/` |
| 共享存储挂载 | `/data/adb/dsha/rootfs/sdcard/` | `/sdcard/`，不存放核心运行时 |

基础控制脚本为 `start.sh`、`stop.sh`、`status.sh`、`term.sh`、`lan-proxy.sh`、`idle-freezer.sh`、`block-guard.sh`，根目录脚本与模块内脚本同步维护。

## 四、维护入口

以下设备命令供维护人员在 Android 宿主终端按需执行；不要在依赖该服务的 Agent 会话中停止或卸载自身运行环境。

```sh
# 查看状态
su -mm -c "/data/adb/dsha/scripts/status.sh"

# 查看宿主日志
su -c "cat /data/adb/dsha/run/dsh-web.log"

# 启动默认 Web 服务
su -mm -c "/data/adb/dsha/scripts/start.sh 3080"

# 进入原生容器终端
su -mm -c "/data/adb/dsha/scripts/term.sh"
```

普通停止服务会保留容器挂载。仅在退出容器终端、结束相关任务后，才可在宿主运行 `stop.sh --umount`；删除运行目录前须确认对应挂载已解绑。

在本地仓库根目录打包：

```sh
bash scripts/build-module.sh --lite
bash scripts/build-module.sh full
```

输出分别为 `dist/dsha_ksu_native_lite.zip` 和 `dist/dsha_ksu_native_full.zip`。Full 原料选择、实机导出要求和对外交付流程见 [BUILD](../BUILD.md)。已有用户的插件身份迁移见 [classic-chat 普通插件迁移](classic-chat-ordinary-plugin.md)。
