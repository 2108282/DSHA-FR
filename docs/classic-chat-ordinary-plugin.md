# classic-chat 改为普通插件

classic-chat 不再属于 DSHA 固有插件。新 Full 包不预装；旧环境刷 Lite 后保留插件版本与启用状态，作为普通插件管理，可禁用、导出、更新、删除。

## Full / Lite 提交位置

遵循 BUILD.md 的现行规则：容器文件位于 `rootfs-overlay/`；Lite 现场动作位于 `magisk-module/patches/`，同时维护根目录 `patches/` 镜像。安装器不硬编码插件名。

- `rootfs-overlay/root/.dsh/rootfs-migrations/ordinary-plugins.py`：幂等迁移工具。普通模式迁移已有安装；`--fresh` 只允许离线目录，清除旧基准底包中的预装实体、注册、清单和立牌。
- `tools/dynamic-rootfs-merge.sh`：复制 overlay 后、扫描插件前，以 `--fresh` 执行迁移目录内的 Python 文件，避免 Full 从旧基准底包重新带回 classic-chat。
- `magisk-module/patches/patch-migrate-ordinary-plugins.sh`：Lite 使用 rootfs 内的 Python 执行迁移，不依赖 Android 宿主安装 Python。
- `magisk-module/customize.sh`：Full 保留数据安装也执行普通模式迁移。
- 注册脚本、自愈脚本、历史补丁与公开底包导出器不再把 classic-chat 自动注册成固有插件。

早期 ARCHITECTURE_AND_PACKAGING.md 中“补丁放 scripts”的说明已过时；实际构建优先打包 `magisk-module/patches/*.sh`，只有模块补丁目录没有脚本时才回退根目录，二者不会合并。

## Lite 的迁移行为

第 1 步选择音量 + 时，先覆盖通用 overlay，再执行补丁：

1. 从 `dsha-builtin.txt` 移除 classic-chat，清除旧 `dsh-classic-chat-installed` 立牌。
2. 将原 `/root/dsh-classic-chat` 权威实体迁入 `/root/.dsh/plugin-src/dsh-classic-chat`。如目标已有旧副本，先留存到 `/root/.dsh/plugin-migration-backups/dsh-classic-chat-*/previous-plugin-src`。若 profile 已明确指向普通版，则保留普通版，旧底模留存为 `previous-builtin`；npm 安装的普通版保持原样。
3. 将旧 `link:/root/dsh-classic-chat` 依赖与两层模块链接对齐到普通插件目录；保持 bundles、禁用标记和其他依赖不变。
4. 没有旧实体时不重新安装；重复执行不新增备份，不重新启用。用户删除普通插件后，再刷 Lite 不会恢复它。

APK 会从自身 assets 覆盖插件注册脚本。因此配套 `magisk-apk` 提交同步去掉 fallback 固有身份，并让显式启用从 plugin-src 解析实体。建议同时更新 APK。旧 APK 配迁移后的非空固有清单仍按普通插件显示，但缺失清单后的 fallback、重新启用解析等行为不能视为已兼容。

第 2 步独立选择是否覆盖 7 个控制脚本：start、stop、status、term、lan-proxy、idle-freezer、block-guard。

Lite 是整个 overlay 与现有补丁的合集，不只包含这次身份变更。当前还带移动端轻量 UI、QuickChat 手势插件、终端命令守卫及原有的挂载超时、提示词、状态广播、SSH 和局域网代理补丁。它不携带新的 DSH / Node / Ubuntu 底包，也不会自动更新 APK。本次不新增 QuickChat 角落呼出等 APK 功能。

## 验证

`python3 scripts/test-ordinary-plugin-migration.py` 使用隔离假 root，覆盖启用/禁用、冲突目录备份、重复执行、删除后不恢复、Full 清理、异常输入、普通插件列表与构建包边界。真机刷入会停止 DSH，需在结束当前会话后进行；隔离验证不等于已完成真机刷入测试。
