# DSHA 原生底包导出与迭代

本文说明如何从已准备好的原生 Ubuntu ARM64 环境导出底包，并生成 KernelSU / Magisk Full 模块。构建与补丁分工见 [BUILD](../BUILD.md)。本地仓库清理不需要升级或修改当前安装的 DSH 环境。

## 一、运行环境

- 根文件系统在 Android 宿主的 `/data/adb/dsha/rootfs/`。
- 容器内 Node.js、pnpm 和 DSH 命令位于 `/usr/local/bin/`。
- 官方 DSH 发布包位于 `/usr/local/lib/node_modules/@deepseek-ai/dsh/`；它是运行依赖，不是本仓库的编辑目标。
- 3095 设备桥凭据位于容器内 `/root/.dsh/.bridge_token`，不能进入公开产物。

若确需升级官方 DSH，先取得单独的维护授权并结束依赖该环境的任务，按官方发布渠道完成升级与验证。不要把全局安装目录当作 Git 工作树执行拉取或本地构建。

## 二、进入导出环境

在 Android 宿主终端进入原生容器：

```sh
su -mm -c "/data/adb/dsha/scripts/term.sh"
```

导出器依赖容器内的 Bash、DSH 与 Linux 文件系统路径，不在 Android 宿主直接运行。在容器内进入实际检出的 `dsh-magisk` 仓库根目录，确认环境与备份已就绪。

## 三、导出与打包

```sh
# 公开发布：排除个人数据和后装插件，并准备纯净 profile
bash tools/export-rootfs.sh

# 导出后联动生成 Full 模块
bash tools/export-rootfs.sh --full

# 个人整机克隆：包含个人插件、配置与会话，不可公开分享
bash tools/export-rootfs.sh --clone
```

脚本只读检查已安装的 DSH，并将脱敏 profile、依赖链接和清单写入临时导出目录及归档。不修补官方包和依赖，不回写正在使用的插件或仓库 overlay；`--full` 只联动本地构建，不部署或重启设备服务。

公开模式在导出流中排除虚拟挂载、临时数据、会话与凭据，注入纯净 profile，并使用可用的 pigz 或 gzip 生成 `rootfs.tar.gz`。公开发布前仍须检查产物，确认没有个人记录、密钥或设备专属凭据。

- 公开模式不携带 classic-chat 的旧实体、安装立牌、普通插件源码及旧 profile 锁文件；Web profile 仅注册当前五个内置插件，避免残留第三方路径。
- Git 登录凭据、远程访问 TLS 私钥/配对状态、维护备份、私人技能副本、后装语音模型和临时 obscura 工具均不进入公开底包。活动 Node 二进制保留，`n` 下载缓存不保留。
- 前代已含的 Java 21 运行时、JADX、REA、DSH 官方生产闭包及共享运行模块继续保留，不因目录大就裁剪。
- pigz 默认使用两个线程；可用 `PIGZ_THREADS=2 bash tools/export-rootfs.sh --output <新产物路径>` 显式控制并行度，降低手机负载。
- 导出前可运行 `python3 scripts/test-rootfs-export.py -v`，用隔离归档验证脱敏排除、内置清单及软链过滤。该测试不读取或修改真实系统文件。

- 默认底包输出：`/sdcard/Download/DSHA/rootfs.tar.gz`，可用 `--output` 指定。
- Full 模块输出：当前仓库的 `dist/dsha_ksu_native_full.zip`。
- Lite 模块不携带新底包，使用 `bash scripts/build-module.sh --lite` 单独构建。

## 四、交付

核对底包和模块校验值后，再按 [BUILD](../BUILD.md) 对应的发布流程交付。公开底包与个人克隆必须分开保管，不能将克隆模式产物用于公开发布。
