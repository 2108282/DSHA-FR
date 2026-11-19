# 后端历史代码清理记录

日期：2026-11-19。基线：`dsh-magisk@511ac6d`，开始时工作树干净。

用户确认仓库已长期脱离最初 fork 上游，旧 APK、旧网站和历史流程不再保留。本轮按当前原生 chroot 模块边界清理，不再给旧功能保留归档入口。

## 修改边界

- 代码清理修改位于本地 `DSHA_magisk` Git 仓库；随后按用户授权实装自有模块文件，结果见末节。
- 未修改已安装的 DSH 官方代码、依赖、运行配置、用户会话或真实插件。
- 没有升级、重装、重启或停止服务；没有自动推送或改写 Git 历史。
- 行为测试仅用临时假 root、临时打包项目和 stub 命令，没有真实执行安装器、导出器或发布流程。

## 删除的 53 个文件

### 旧 APK / PRoot 工具、失效流水线、过时补丁及退役预装标记（14 个）

```text
tools/build-low-proot.py
tools/build-standard-runtime.py
tools/audit-standard-apk.py
tools/prepare-standard-assets.py
tools/TarRepro.java
tools/rebuild-dsh.py
tools/inject-arm64-natives.py
tools/test-restore-merge-wsdir.py
scripts/ci/android-build.yml
.github/workflows/sync-base-rootfs.yml
patches/patch-sync-rm-one-file-system-guard.sh
magisk-module/patches/patch-sync-rm-one-file-system-guard.sh
patches/patch-sync-quickchat-gesture-builtin.sh
rootfs-overlay/root/dsh-classic-chat-installed
```

旧 APK 工具依赖本分支不存在的 app/Gradle/离线资产，当前后端与统一总装没有调用。旧 rm 补丁可能以旧模板覆盖新版动态守卫；其功能已由 overlay 和启动脚本实现。旧 quickchat 补丁的必要源码清理和客户端时间戳刷新已并入当前单源补丁，其重复注册与双源复制逻辑一并移除。

### 旧双 APK 网站整体（22 个）

```text
website/.gitignore
website/README.md
website/data/catalog.mjs
website/deploy/PUBLISHING.md
website/deploy/nginx-dsha-https.conf
website/deploy/update-web-release.sh
website/package.json
website/scripts/build.mjs
website/scripts/http-check.mjs
website/scripts/install-page.mjs
website/scripts/package.mjs
website/scripts/serve.mjs
website/scripts/site.test.mjs
website/scripts/verify-copy.mjs
website/src/app.js
website/src/community/dsh-session-health.png
website/src/community/dsh-subagent-model-picker.png
website/src/install.js
website/src/skills/device-shell/SKILL.md
website/src/skills/screen-ocr-operator/SKILL.md
website/src/styles.css
website/src/theme.js
```

这组代码使用旧包名 com.dsh.client 和 standard/low 双 APK 发布契约，已与当前 com.dsha.fr 原生模块分离；按用户追加指示整组删除，而非继续标为历史入口。

### 旧网站配套生成器及 JNI 复现副本（8 个）

```text
tools/generate-release-manifest.py
scripts/test-release-manifest.py
scripts/generate-update-fixtures.py
tools/termux-jni/LICENSE-2.0.txt
tools/termux-jni/LICENSE.upstream.md
tools/termux-jni/README.md
tools/termux-jni/build.ps1
tools/termux-jni/termux.c
```

JNI 源码与其专属许可证随这组无用副本完整移除；仓库根许可证及其版权归属保留。没有删除前端工作树中的实际工具、JNI 库或源码。

### 过时文档、统计资产和旧手机技能（9 个）

```text
docs/ROADMAP.md
docs/plugins.md
docs/security-model.md
docs/security-model.en.md
docs/star-history.json
docs/star-history.svg
docs/star-history-dark.svg
agent-skills/device-shell/SKILL.md
agent-skills/screen-ocr-operator/SKILL.md
```

当前 external-device-mesh 技能原文保留；手机任务继续通过现有 MCP 与设备引导插件处理。

## 同步修复

1. **去掉对官方安装的侵入式修补**：从自有导出工具删除恢复 .ignored_ 核心包、删除嵌套依赖目录的旧逻辑；从自有安装器删除同类核心包重命名修补；从两份启动器删除直接向旧官方 index.html 注入样式的补丁。官方环境由官方工具维护，模块脚本不再自动改其依赖布局或前端产物。此项改的是仓库脚本，没有执行真实环境变更。
2. **导出职责收敛**：移除导出时把工作区/plugin-src 插件自动覆盖回运行环境及仓库 overlay 的 Bake-in 逻辑。导出仅保留只读健康检查、脱敏、临时目录处理与产物生成。
3. **日志断链修复**：容器内 `/root/dsh-web.log` 是实际日志文件，宿主 `/data/adb/dsha/run/dsh-web.log` 是指向该实体的软链，保留前端两种读取接口。日志初始化仍按原设计在启动前清空旧日志。
4. **Lite 补丁目录传递**：显式导出 TMP_STAGE，子补丁读取父安装器的真实解压目录。
5. **预装残留清理**：除删除 Classic Chat 安装标记，还从 overlay 的内置清单移除其条目，模块描述不再宣传该插件为内置。保留旧用户普通插件迁移，保护既有安装、启用/禁用状态及用户删除选择。
6. **镜像一致性**：根 term.sh 追平现有模块镜像较新的存储探活实现；当前打包实现不变。保留构建需要的脚本和补丁镜像。
7. **发布旧常量与产物路径**：发布工具默认读取仓库 dist/rootfs.tar.gz，去掉硬编码的旧 DSH/DSHA 版本描述，使用实际 tag 与产物信息。替换分卷前清理旧生成分卷，避免新包变小后遗留尾卷导致合并损坏；不删除无关文件。
8. **旧目录与测试回退**：去掉旧 dsha-ksu-project/release 底包搜索路径；插件管理测试直接加载当前 overlay，去掉不存在的 app 路径和相邻工作区回退。
9. **维护说明与忽略规则**：README、BUILD、架构说明、项目地图、导出指南、技能入口统一到当前模块；清掉 Gradle/双 APK/旧网站忽略项，加入当前底包和模块生成物。仓库 AGENTS 同步当前五个内置插件布局和有效导出命令，保留原有安全约束。
10. **内存注释**：仅纠正 memcg “锁定物理内存/禁止换页”的保证性文字，没有改变内存限制、分配器或换页策略。

## 最终验证

基线 189 个跟踪文件，删除 53 个，保留 136 个原跟踪文件，另新增本记录。

OCR 差异清单共 78 项，其中自动纳入审查的代码文件 total_files=14、reviewed_files=14、skipped_files=0、coverage_rate=100%。工具因删除状态、扩展名或二进制而排除的 64 项另行核对了删除依据和文档引用；其中两张旧网站 PNG 只核对资源归属与引用，不作图片内容审查。这个覆盖率只表示本轮差异清单的处理情况，不表示所有动态分支已被穷尽。

| 检查 | 结果 |
|---|---:|
| 普通插件迁移与分发回归 | 17 项通过 |
| 插件管理回归 | 18 项通过 |
| 终端守卫回归 | 20 项通过 |
| 闲置冻结回归 | 27 项通过 |
| 合计 | 82 项通过 |
| 守卫生成器只读校验 | 21 个文件一致 |
| Python AST | 11 个当前文件通过 |
| Shell 语法 | 61 个当前文件通过 |
| JavaScript / MJS 语法 | 7 个当前文件通过 |
| JSON | 7 个当前文件通过 |
| 当前文档 Markdown 相对链接 | 12 条有效 |
| 控制脚本与补丁镜像 | 同名文件字节一致 |
| 三组 overlay 插件 main/types/patch 入口 | 文件存在 |

新增回归覆盖：Lite 产物保留当前守卫且不包含旧补丁和退役预装；父子进程 stage 传递；旧源码清理不跟随重定向目录；日志双端读写与重复初始化；导出和安装预处理不改写假官方依赖树；替换分卷不遗留旧尾卷、不删除无关文件。

## 有意保留与验证边界

- 当前自有生命周期脚本、手机 MCP relay、网络代理、闲置冻结、命令守卫、插件管理及 overlay 插件保留。
- 普通插件迁移和离线底包中旧痕迹的净化规则仍有作用，不将“处理旧用户数据”误当成旧功能依赖。
- DSH 官方包及正常外部依赖引用保留；Git 提交历史和根许可证保留。
- TypeScript/原生第三方实现未作为本轮重构目标；未宣称动态加载分支或未知外部调用均已穷尽。
- 未执行完整约 200 MB 底包构建、云端发布或完整模块刷入。后续用户授权的本机文件实装结果如下。

## 本机实装（用户后续授权）

2026-11-19 已先备份，再原子更新以下三个目录中的 start.sh、term.sh，共六份自有脚本：

```text
/data/adb/dsha/scripts/
/data/adb/modules/dsha_native/scripts/
/data/adb/modules_update/dsha_native/scripts/
```

- 三处脚本均已回读校验，与仓库源码逐字节一致；权限和属主保持原样。
- 日志修复已在线生效：将当前日志 inode 保留为容器内实际文件，再切换宿主软链，未复制丢失增量、未清空日志；主进程 stdout 仍指向同一 inode。
- 内置清单原本已与仓库一致，退役 Classic Chat 标记原本不存在，因此没有为展示操作而重写这些文件，也未改用户插件。
- 保留已安装模块的版本号和动态运行状态描述，不以静态源码覆盖运行状态。
- 主进程 PID 和启动时间保持不变。未带凭据访问本地服务返回 401，确认服务仍响应且鉴权门禁保留；这不等同于完整 Web UI 端到端测试。
- 官方包清单和 CLI 入口的 SHA-256、修改时间均保持不变；部署写入范围仅为上述自有脚本及自有日志路径。
- 实装后再次运行 82 项隔离回归及 21 文件守卫一致性检查，全部通过。
- 启动器已经落盘，其启动时逻辑将在下次正常启动时执行；本次没有重启运行进程。安装器、构建、导出和发布工具使用仓库中的新源码，不另行刷入完整模块。

部署前文件与执行清单保存在本机维护目录的独立备份中，具体位置在本次交付说明中提供。
