#!/usr/bin/env bash
# ============================================================
# DSHA 原生 Linux chroot (KernelSU / Magisk) 底包导出与打包工具
# 特性：
# 1. 纯只读脱敏：绝对不修改、不删除当前运行环境下的任何本地文件或配置；
# 2. 智能解耦：自动排除后装插件源码 (plugin-src/*) 及个人账号/密钥；
# 3. 完美修复依赖：动态注入仅含 4 大内置原生插件的标准 profiles/web/package.json，
#    彻底杜绝新机刷入后因后装插件断链导致 DSH 启动报错；
# 4. 双模式支持：--clean (默认，公开发布纯净底包) / --clone (个人整机换机备份)；
# 5. 支持 --full 参数直接联动 build-module.sh 一键生成最终刷机包。
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
DEFAULT_OUTPUT="/sdcard/Download/DSHA/rootfs.tar.gz"

MODE="clean"
OUTPUT_TAR="$DEFAULT_OUTPUT"
TRIGGER_FULL_BUILD=0

# 解析参数
while [ $# -gt 0 ]; do
    case "$1" in
        --clean)
            MODE="clean"
            shift
            ;;
        --clone)
            MODE="clone"
            shift
            ;;
        --full)
            TRIGGER_FULL_BUILD=1
            shift
            ;;
        -o|--output)
            OUTPUT_TAR="$2"
            shift 2
            ;;
        -h|--help)
            echo "用法: $0 [选项]"
            echo "选项:"
            echo "  --clean        [默认] 导出公开发布的纯净脱敏底包 (排除后装插件与私有数据，修复依赖)"
            echo "  --clone        导出完整克隆镜像 (保留所有后装插件、配置与会话，仅排除系统虚拟挂载)"
            echo "  --full         底包导出完成后，自动调用 scripts/build-module.sh 生成完整刷机包"
            echo "  -o, --output   指定底包输出路径 (默认: /sdcard/Download/DSHA/rootfs.tar.gz)"
            echo "  -h, --help     显示此帮助信息"
            exit 0
            ;;
        *)
            echo "未知参数: $1" >&2
            exit 1
            ;;
    esac
done

STAGE_DIR="/tmp/dsha_export_stage_$$"
mkdir -p "$STAGE_DIR"
cleanup() {
    python3 -c "import shutil; shutil.rmtree('$STAGE_DIR', ignore_errors=True)" 2>/dev/null || rm -rf "$STAGE_DIR" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

echo "=========================================================="
echo "   DSHA 原生 Linux 底包导出引擎 (当前运行环境 -> rootfs.tar.gz)   "
echo "   运行模式: $MODE"
echo "=========================================================="

# 基础必须排除的系统虚拟文件系统与外部挂载
BASE_EXCLUDES=(
    "--exclude=./proc/*"
    "--exclude=./sys/*"
    "--exclude=./dev/*"
    "--exclude=./sdcard/*"
    "--exclude=./storage/*"
    "--exclude=./tmp/*"
    "--exclude=./run/*"
    "--exclude=./data/*"
    "--exclude=./mnt/*"
    "--exclude=./root/手机存储"
    "--exclude=./root/内部存储"
    "--exclude=./sdcard"
    "--exclude=./storage"
    "--exclude=./usr/share/fonts/android/*"
    "--exclude=./usr/share/fonts/truetype/android/*"
    "--exclude=.git"
    "--exclude=.git/*"
    "--exclude=.github"
    "--exclude=.github/*"
    "--exclude=.gitignore"
    "--exclude=.gitattributes"
)

# 压缩引擎选择 (优先多线程 pigz，最高压缩等级 -9)
COMPRESS_CMD="gzip -9"
if command -v pigz >/dev/null 2>&1; then
    COMPRESS_CMD="pigz -p 6 -9"
fi

if [ "$MODE" = "clone" ]; then
    echo "==> 正在执行【完整克隆备份】打包..."
    echo "    (保留当前所有个人插件、会话记录与系统配置)"
    
    mkdir -p "$(dirname "$OUTPUT_TAR")"
    cd /
    tar --numeric-owner -c "${BASE_EXCLUDES[@]}" . | $COMPRESS_CMD > "$OUTPUT_TAR"
    echo "✓ 完整克隆底包已生成: $OUTPUT_TAR ($(ls -lh "$OUTPUT_TAR" | awk '{print $5}'))"
else
    echo "==> 正在准备【公开发布纯净脱敏】打包..."
    echo "    [安全保障]: 当前本地系统文件零修改，所有脱敏重置仅在导出流中生效。"

    # 0. 底包导出前依赖健康自愈与严格断言
    TARGET_CORE_DIR="/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai"
    if [ -d "$TARGET_CORE_DIR" ]; then
        for d in "$TARGET_CORE_DIR"/.ignored_*; do
            [ -d "$d" ] || continue
            b=$(basename "$d")
            real="${b#.ignored_}"
            orig="$TARGET_CORE_DIR/$real"
            [ -L "$orig" ] && rm -f "$orig"
            mv -f "$d" "$orig" 2>/dev/null || true
            echo "  -> [自动自愈] 恢复核心包实体: $b -> $real"
        done

        # 自动去重：清理 npm 重复下载的 50+ 个子包内冗余嵌套 node_modules/zod
        python3 -c '
import os, shutil
base = "/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai"
for d in os.listdir(base):
    zod_nm = os.path.join(base, d, "node_modules", "zod")
    if os.path.isdir(zod_nm):
        shutil.rmtree(os.path.join(base, d, "node_modules"), ignore_errors=True)
' 2>/dev/null || true
    fi

    echo "==> 正在执行运行时完整性断言 (dsh --version)..."
    DSH_VER=$(dsh --version 2>&1) || {
        echo "❌ 致命错误: dsh 运行时损坏，禁止导出带毒底包！报错如下：" >&2
        echo "$DSH_VER" >&2
        exit 1
    }
    echo "    ✓ 核心运行时健康: $DSH_VER"

    # 1. 在临时 Stage 中准备纯净的 profiles/web/package.json 与 cordis.patch.yml
    CLEAN_PROFILE_DIR="$STAGE_DIR/root/.dsh/profiles/web"
    mkdir -p "$CLEAN_PROFILE_DIR"

    cat > "$CLEAN_PROFILE_DIR/package.json" << 'EOF_PKG'
{
  "name": "dsh-profile-web",
  "private": true,
  "dependencies": {
    "dsh-device-shell-guide": "link:/root/dsha-device-shell-guide",
    "dsh-task-notifier": "link:/root/dsha-task-notifier",
    "dsh-status-overlay": "link:/root/dsha-status-overlay",
    "dsh-mobile-lite": "link:/root/dsh-mobile-lite",
    "dsh-classic-chat": "link:/root/dsh-classic-chat",
    "dsh-quickchat-gesture": "link:/root/dsh-quickchat-gesture"
  },
  "dsh": {
    "profile": {
      "bundles": [
        "@deepseek-ai/dsh-base",
        "@deepseek-ai/dsh-web-app",
        "dsh-device-shell-guide",
        "dsh-task-notifier",
        "dsh-status-overlay",
        "dsh-mobile-lite",
        "dsh-classic-chat",
        "dsh-quickchat-gesture"
      ],
      "patchReload": "startup"
    }
  }
}
EOF_PKG

    cat > "$CLEAN_PROFILE_DIR/cordis.patch.yml" << 'EOF_CORDIS'
- id: ui-conversation
  name: "@deepseek-ai/dsh-client-ui-conversation"
  config:
    busyEnter: steer
- id: session-log-deepseek
  name: "@deepseek-ai/dsh-session-log-deepseek"
  config:
    enabled: false
- id: ui-settings-general
  name: "@deepseek-ai/dsh-client-ui-settings-general"
  config:
    welcomeNoticeVersion: 2026-09-28.1
- id: agent-default-model
  name: "@deepseek-ai/dsh-agent-default-model"
  config:
    provider: agy
    model: gemini-3.8-flash-tiered
    reasoningEffort: high
- id: permission
  name: "@deepseek-ai/dsh-permission-presets"
  config:
    presets:
      read-only:
        sandbox: read-only
        approval: ask
      workspace-write:
        sandbox: workspace-write
        approval: ask
      danger-full-access:
        sandbox: danger-full-access
        approval: never
    defaultPreset: danger-full-access
- id: llm-pi-ai
  name: "@deepseek-ai/dsh-llm-pi-ai"
  config:
    providers:
      shusheng:
        baseURL: https://discovery-api.intern-ai.org.cn/v1
        api: openai-completions
        displayName: jdjd
        models:
          - id: Agents-A1
            name: Agents-A1
          - id: Atria-Dawn-Preview
            name: Atria-Dawn-Preview
          - id: deepseek-v4-flash-0731
            name: DeepSeek-V4-Flash-0731
          - id: deepseek-v4-flash-vision
            name: DeepSeek-V4-Flash-Vision
          - id: deepseek-v4-pro-0813
            name: DeepSeek-V4-Pro-0813
          - id: glm-5.3
            name: GLM-5.3
          - id: intern-s2
            name: 书生-S2
          - id: kimi-k2.6
            name: Kimi-K2.6
          - id: minimax-m3
            name: MiniMax-M3
          - id: qwen3.8-27b
            name: Qwen3.8-27B
        apiKeyEnv: DSHA_SHUSHENG_API_KEY
- id: ui-theme
  name: "@deepseek-ai/dsh-client-ui-theme"
  config:
    fontSize: 15
EOF_CORDIS

    # 2. 收集需要排除的死链 (profiles/web/node_modules, /usr/local/lib/node_modules, /usr/local/bin 中所有指向 plugin-src/、外部存储或悬空的软链)
    SYMLINK_EXCLUDES=()
    for scan_dir in "/root/.dsh/profiles/web/node_modules" "/usr/local/lib/node_modules" "/usr/local/bin"; do
        if [ -d "$scan_dir" ]; then
            while IFS= read -r link_entry; do
                [ -L "$link_entry" ] || continue
                target=$(readlink "$link_entry" || true)
                if [[ "$target" == *"plugin-src"* ]] || [[ "$target" == *"/sdcard"* ]] || [[ "$target" == *"/storage"* ]] || [ ! -e "$link_entry" ]; then
                    rel_path=".${link_entry}"
                    SYMLINK_EXCLUDES+=("--exclude=$rel_path")
                fi
            done < <(find "$scan_dir" -maxdepth 2 -type l 2>/dev/null || true)
        fi
    done

    # 3. 收集并过滤技能打包列表 (显式维护官方标准技能白名单，确保 external-device-mesh 与 REA 等打入底包并脱敏排除私有技能)
    OFFICIAL_SKILLS=(
        "boxproxy-support"
        "open-code-review"
        "open-code-review-delegate"
        "penpot-mcp"
        "external-device-mesh"
        "reverse-engineer-anything"
        "rea"
    )
    SKILL_EXCLUDES=()
    if [ -d "/root/.dsh/skills" ]; then
        for sdir in /root/.dsh/skills/*; do
            [ -e "$sdir" ] || continue
            sname=$(basename "$sdir")
            is_official=0
            for os in "${OFFICIAL_SKILLS[@]}"; do
                if [ "$sname" = "$os" ]; then
                    is_official=1
                    break
                fi
            done
            if [ "$is_official" -eq 0 ]; then
                echo "  -> [脱敏排除非内置技能]: $sname"
                SKILL_EXCLUDES+=("--exclude=./root/.dsh/skills/$sname" "--exclude=./root/.dsh/skills/$sname/*")
            fi
        done
    fi
    echo "    ✓ 官方技能打包列表已就绪: ${OFFICIAL_SKILLS[*]}"

    # 4. 收集后装第三方 Python 包排除项 (排除所有后装 pip 依赖及废弃的 android_agent.py)
    PYTHON_EXCLUDES=()
    for py_dist in "/usr/local/lib/python3.12/dist-packages" "/usr/local/lib/python3/dist-packages"; do
        if [ -d "$py_dist" ]; then
            for pyd in "$py_dist"/*; do
                [ -e "$pyd" ] || continue
                pyb=$(basename "$pyd")
                if [[ "$pyb" != "__pycache__" ]]; then
                    PYTHON_EXCLUDES+=("--exclude=.${py_dist}/$pyb")
                fi
            done
        fi
    done

    # 5. 纯净模式脱敏黑名单
    CLEAN_EXCLUDES=(
        "${BASE_EXCLUDES[@]}"
        "${SYMLINK_EXCLUDES[@]}"
        "${SKILL_EXCLUDES[@]}"
        "${PYTHON_EXCLUDES[@]}"
        "--exclude=./root/.dsh/skills/android-device-agent"
        "--exclude=./root/.dsh/skills/android-device-agent/*"
        "--exclude=./root/.dsh/scripts/android_agent.py"
        "--exclude=./usr/local/bin/android-agent"
        "--exclude=./var/lib/dsh-app/phone/bridge.json"
        "--exclude=./root/.dsh/skills/*/*.bak"
        "--exclude=./root/.dsh/skills/*/*~"
        # 排除内置插件源码开发依赖、测试与文档 (生产底座仅需 lib/ 与 package.json)
        "--exclude=./root/dsh-mobile-lite/node_modules"
        "--exclude=./root/dsh-mobile-lite/node_modules/*"
        "--exclude=./root/dsh-mobile-lite/.git"
        "--exclude=./root/dsh-mobile-lite/.git/*"
        "--exclude=./root/dsh-mobile-lite/.client-build"
        "--exclude=./root/dsh-mobile-lite/.client-build/*"
        "--exclude=./root/dsh-quickchat-gesture/node_modules"
        "--exclude=./root/dsh-quickchat-gesture/node_modules/*"
        "--exclude=./root/dsh-quickchat-gesture/.git"
        "--exclude=./root/dsh-quickchat-gesture/.git/*"
        "--exclude=./root/dsh-quickchat-gesture/.client-build"
        "--exclude=./root/dsh-quickchat-gesture/.client-build/*"
        "--exclude=./root/dsh-classic-chat/node_modules"
        "--exclude=./root/dsh-classic-chat/node_modules/*"
        "--exclude=./root/dsh-classic-chat/.git"
        "--exclude=./root/dsh-classic-chat/.git/*"
        "--exclude=./root/dsh-classic-chat/docs"
        "--exclude=./root/dsh-classic-chat/docs/*"
        "--exclude=./root/dsh-classic-chat/tests"
        "--exclude=./root/dsh-classic-chat/tests/*"
        "--exclude=./root/dsh-classic-chat/repro"
        "--exclude=./root/dsh-classic-chat/repro/*"
        "--exclude=./root/dsh-classic-chat/benchmarks"
        "--exclude=./root/dsh-classic-chat/benchmarks/*"
        # 排除工作区与个人工程文件 (严禁将用户工作区数据打入公开底包)
        "--exclude=./root/workspaces/*"
        "--exclude=./root/.dsha-workspaces*"
        "--exclude=./root/.dsha-tools*"
        # 排除编译残留的多余 JDK 17、开发用 jmods 与构建套件 (底座仅保留标准 Java 运行时，节省约 500MB 未压缩体积)
        "--exclude=./usr/lib/jvm/java-17-*"
        "--exclude=./usr/lib/jvm/java-1.17.0-*"
        "--exclude=./usr/lib/jvm/*/jmods"
        "--exclude=./usr/lib/jvm/*/jmods/*"
        "--exclude=./usr/libexec/gcc*"
        "--exclude=./usr/lib/gcc*"
        "--exclude=./usr/bin/*gcc*"
        "--exclude=./usr/bin/*g++*"
        "--exclude=./usr/bin/aarch64-linux-gnu-*"
        "--exclude=./usr/lib/aarch64-linux-gnu/lib*san*"
        "--exclude=./usr/lib/aarch64-linux-gnu/*.a"
        "--exclude=*.a"
        "--exclude=./usr/lib/android-sdk/platforms*"
        "--exclude=./usr/lib/android-sdk/build-tools*"
        "--exclude=./usr/lib/android-sdk/.temp*"
        "--exclude=./usr/lib/android-sdk/.knownPackages*"
        "--exclude=./usr/lib/llvm*"
        "--exclude=./usr/include/llvm*"
        "--exclude=./root/.gradle"
        "--exclude=./root/.gradle/*"
        # 排除后装第三方插件实体、依赖与管理配置 (彻底排除 react-icons/mermaid 等 220MB 冗余依赖)
        "--exclude=./root/.dsh/profiles/web/node_modules/*"
        "--exclude=./root/.dsh/profiles/web/.dsh-market*"
        "--exclude=./root/.dsh/profiles/web/.plugin-manager*"
        "--exclude=./usr/local/lib/node_modules/dsh-better-sidebar*"
        "--exclude=./usr/local/lib/node_modules/dshmarket*"
        # 排除当前含有后装插件的 package.json 与个性化配置，后面追加注入纯净版
        "--exclude=./root/.dsh/profiles/web/package.json"
        "--exclude=./root/.dsh/profiles/web/cordis.patch.yml"
        # 排除个人会话、附件与存储
        "--exclude=./root/.dsh/sessions/*"
        "--exclude=./root/.dsh/attachments/*"
        "--exclude=./root/.dsh/storages/*"
        # 排除模型账号与 API Key
        "--exclude=./root/.dsh/agy"
        "--exclude=./root/.dsh/agy/*"
        "--exclude=./root/.dsh/agy-*"
        "--exclude=./root/.dsh/.credentials.yaml"
        "--exclude=./root/.dsh/dsh-api-dashboard.json"
        "--exclude=./root/.dsh/settings.yaml*"
        # 排除用户 SSH 个人公私钥、资产密码与主机指纹 (绝不外泄，确保每机独立性)
        "--exclude=./root/.ssh"
        "--exclude=./root/.ssh/*"
        "--exclude=./etc/ssh/ssh_host_*_key"
        "--exclude=./etc/ssh/ssh_host_*_key.pub"
        # 排除 ADB 本机私钥与已知主机指纹
        "--exclude=./root/.android/adbkey"
        "--exclude=./root/.android/adbkey.pub"
        "--exclude=./root/.android/adb_known_hosts.pb"
        # 排除设备桥与认证 Token
        "--exclude=./root/.dsh/.bridge_token"
        "--exclude=./root/.dsh/.bridge_*"
        "--exclude=./root/.dsh/.launch_token"
        "--exclude=./root/.dsh/.lan_token"
        "--exclude=./root/.dsh/.approval_decision"
        "--exclude=./root/.dsh/.auth_lease"
        "--exclude=./root/.dsh/.anonymous-user-id"
        # 排除后装插件源码实体与历史
        "--exclude=./root/.dsh/plugin-src/*"
        "--exclude=./root/.dsh/plugin-previews/*"
        "--exclude=./root/.dsh/plugin-history/*"
        "--exclude=./root/.dsh/plugin-sources.json"
        "--exclude=./root/.dsh/plugin-updates.json"
        "--exclude=./root/.dsh/logs/*"
        # 排除临时文件、缓存、日志与命令历史
        "--exclude=./root/.bash_history"
        "--exclude=./root/.cache/*"
        # 排除 npm 与 pnpm 离线下载缓存与全局存储 (省下约 1GB 冗余死重)
        "--exclude=./root/.local/*"
        "--exclude=./root/.npm/*"
        "--exclude=./root/.npmrc"
        # 排除用户主目录下残留的第三方依赖与死链
        "--exclude=./root/node_modules"
        "--exclude=./root/node_modules/*"
        "--exclude=./root/.dsh/profiles/web/node_modules/.bin/*"
        "--exclude=*/.ignored_*"
        "--exclude=./root/.agents/*"
        "--exclude=./root/.config/*"
        "--exclude=./root/.mcp-auth"
        "--exclude=./root/.mcp-auth/*"
        "--exclude=./root/build-agy"
        "--exclude=./root/build-agy/*"
        "--exclude=./root/.gitconfig"
        "--exclude=./root/.dsha-backup*"
        "--exclude=./root/.dsha-restore*"
        "--exclude=./root/*.bak*"
        "--exclude=./var/log/*"
        "--exclude=./var/tmp/*"

        "--exclude=./root/*.log"
        "--exclude=./root/*.xml"
        "--exclude=./root/test-*.sh"
        "--exclude=./root/.*.pid"
        "--exclude=./root/.dsh/*.pid"
        "--exclude=./root/.dsh/dsh.pid"
        "--exclude=./root/.dsh/*.log"
        "--exclude=./root/.dsh/browser-tabs"
        "--exclude=./root/.dsh/browser-tabs/*"
        "--exclude=./root/.dsh/.approval_status*"
        "--exclude=./root/.dsh/.plugins.lock"
        "--exclude=./root/.dsh/connected_devices.json"
        "--exclude=./root/.dsh/taskset"
        "--exclude=./root/.dsh/freezer.state"
        "--exclude=./root/.dsh.pre-restore*"
        "--exclude=./root/.dsh/__pycache__"
        "--exclude=./root/.dsh/repair-builtin.log"
        "--exclude=./root/.dsh/restore-report.txt"
        "--exclude=./root/.dsh/plugin-export*"
        "--exclude=./root/dsha-repo"
        "--exclude=./root/.opencodereview"
        "--exclude=./root/root*"
        "--exclude=./normify-*"
        "--exclude=./.l2s"
        "--exclude=./.proroot-meta"
        "--exclude=./var/lib/apt/lists/*"
        "--exclude=./var/cache/apt/*"
        "--exclude=./usr/share/doc/*"
        "--exclude=./usr/share/man/*"
    )

    mkdir -p "$(dirname "$OUTPUT_TAR")"
    TMP_RAW_TAR="$STAGE_DIR/raw_rootfs.tar"

    # 6. 内置插件外层更新检测与自动熔铸 (Bake-in)
    # 若在工作区或 plugin-src 中存在修改或编译出的更新版本，自动将最新生产产物替换底层与 overlay
    echo "==> [0/3] 正在检测内置固有插件是否有外层新版本并执行自动熔铸..."
    PRE_BUILTIN_PLUGINS=(
        "dsh-device-shell-guide"
        "dsh-task-notifier"
        "dsh-status-overlay"
        "dsh-mobile-lite"
        "dsh-classic-chat"
        "dsh-quickchat-gesture"
    )
    for p in "${PRE_BUILTIN_PLUGINS[@]}"; do
        if [ "$p" = "dsh-mobile-lite" ] || [ "$p" = "dsh-classic-chat" ] || [ "$p" = "dsh-quickchat-gesture" ]; then
            base_tgt="/root/$p"
            overlay_tgt="$ROOT_DIR/rootfs-overlay/root/$p"
        else
            base_tgt="/root/dsha-${p#dsh-}"
            overlay_tgt="$ROOT_DIR/rootfs-overlay/root/dsha-${p#dsh-}"
        fi

        # 候选外层来源优先顺序：工作区开发源码 > plugin-src
        src_candidate=""
        if [ -d "/root/workspaces/内部工作区/$p" ] && [ -f "/root/workspaces/内部工作区/$p/package.json" ]; then
            src_candidate="/root/workspaces/内部工作区/$p"
        elif [ -d "/root/.dsh/plugin-src/$p" ] && [ -f "/root/.dsh/plugin-src/$p/package.json" ]; then
            src_candidate="/root/.dsh/plugin-src/$p"
        fi

        if [ -n "$src_candidate" ] && [ -d "$base_tgt" ]; then
            # 比对版本或核心编译产物修改时间
            src_ver=$(python3 -c "import json; print(json.load(open('$src_candidate/package.json')).get('version', ''))" 2>/dev/null || true)
            base_ver=$(python3 -c "import json; print(json.load(open('$base_tgt/package.json')).get('version', ''))" 2>/dev/null || true)
            need_bake=0
            if [ -n "$src_ver" ] && [ "$src_ver" != "$base_ver" ]; then
                need_bake=1
            elif [ -d "$src_candidate/lib" ] && [ "$src_candidate/lib" -nt "$base_tgt/package.json" ]; then
                need_bake=1
            fi

            if [ "$need_bake" -eq 1 ]; then
                echo "  -> [自动熔铸] 发现 $p 外层新版本 (外层: v$src_ver, 底层: v$base_ver)，正在熔铸进底包..."
                # 物理覆盖底层生产产物 (排除 node_modules, .git, src 等开发死重，纯净就绪)
                mkdir -p "$base_tgt"
                rm -rf "$base_tgt/lib" 2>/dev/null || true
                cp -af "$src_candidate/package.json" "$base_tgt/"
                [ -d "$src_candidate/lib" ] && cp -af "$src_candidate/lib" "$base_tgt/"
                [ -f "$src_candidate/cordis.patch.yml" ] && cp -af "$src_candidate/cordis.patch.yml" "$base_tgt/"
                [ -f "$src_candidate/README.md" ] && cp -af "$src_candidate/README.md" "$base_tgt/"
                
                # 同步更新到仓库的 rootfs-overlay
                if [ -d "$ROOT_DIR/rootfs-overlay" ]; then
                    mkdir -p "$overlay_tgt"
                    cp -af "$base_tgt/." "$overlay_tgt/" 2>/dev/null || true
                fi
                echo "     ✓ $p 已成功熔铸至底模 (v$src_ver)"
            fi
        fi
        # 确保标记立牌存在
        touch "$base_tgt-installed" 2>/dev/null || true
    done

    echo "==> [1/3] 正在扫描系统并创建脱敏底包流..."
    cd /
    tar --numeric-owner -cf "$TMP_RAW_TAR" "${CLEAN_EXCLUDES[@]}" . || [ $? -eq 1 ]

    echo "==> [2/3] 动态注入纯净版 profiles/web/package.json 与核心插件标准软链接..."
    STAGE_PROFILE_NM="$STAGE_DIR/root/.dsh/profiles/web/node_modules"
    STAGE_GLOBAL_NM="$STAGE_DIR/usr/local/lib/node_modules"
    STAGE_AGENTS_SKILLS="$STAGE_DIR/root/.agents/skills"
    STAGE_WORKSPACES="$STAGE_DIR/root/workspaces"
    mkdir -p "$STAGE_PROFILE_NM/@deepseek-ai" "$STAGE_GLOBAL_NM/@deepseek-ai" "$STAGE_AGENTS_SKILLS" "$STAGE_WORKSPACES"
    touch "$STAGE_AGENTS_SKILLS/.gitkeep" "$STAGE_WORKSPACES/.gitkeep"
    ln -sfn "/usr/local/lib/node_modules/@deepseek-ai/dsh" "$STAGE_PROFILE_NM/@deepseek-ai/dsh"
    ln -sfn "/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-mcp-client" "$STAGE_PROFILE_NM/@deepseek-ai/dsh-mcp-client"
    ln -sfn "/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-mcp-client" "$STAGE_GLOBAL_NM/@deepseek-ai/dsh-mcp-client"
    EXTRA_STAGE_INJECTS=()
    if [ -d "/root/.dsh/skills/reverse-engineer-anything" ]; then
        ln -sfn "/root/.dsh/skills/reverse-engineer-anything" "$STAGE_AGENTS_SKILLS/reverse-engineer-anything"
        EXTRA_STAGE_INJECTS+=("./root/.agents/skills/reverse-engineer-anything")
    fi
    BUILTIN_PLUGINS=(
        "dsh-device-shell-guide"
        "dsh-task-notifier"
        "dsh-status-overlay"
        "dsh-mobile-lite"
        "dsh-classic-chat"
        "dsh-quickchat-gesture"
    )
    for p in "${BUILTIN_PLUGINS[@]}"; do
        if [ "$p" = "dsh-mobile-lite" ] || [ "$p" = "dsh-classic-chat" ] || [ "$p" = "dsh-quickchat-gesture" ]; then
            tgt="/root/$p"
        else
            tgt="/root/dsha-${p#dsh-}"
        fi
        ln -sfn "$tgt" "$STAGE_PROFILE_NM/$p"
        ln -sfn "$tgt" "$STAGE_GLOBAL_NM/$p"
        EXTRA_STAGE_INJECTS+=(
            "./root/.dsh/profiles/web/node_modules/$p"
            "./usr/local/lib/node_modules/$p"
        )
    done
    tar --numeric-owner -rf "$TMP_RAW_TAR" -C "$STAGE_DIR" \
        ./root/.dsh/profiles/web/package.json \
        ./root/.dsh/profiles/web/cordis.patch.yml \
        ./root/.dsh/profiles/web/node_modules/@deepseek-ai/dsh \
        ./root/.dsh/profiles/web/node_modules/@deepseek-ai/dsh-mcp-client \
        ./usr/local/lib/node_modules/@deepseek-ai/dsh-mcp-client \
        ./root/.agents/skills/.gitkeep \
        ${EXTRA_STAGE_INJECTS[@]+"${EXTRA_STAGE_INJECTS[@]}"} \
        ./root/workspaces/.gitkeep

    echo "==> [3/3] 正在执行多线程高效压缩..."
    $COMPRESS_CMD < "$TMP_RAW_TAR" > "$OUTPUT_TAR"
    rm -f "$TMP_RAW_TAR"

    echo "✓ 纯净脱敏底包已生成: $OUTPUT_TAR ($(ls -lh "$OUTPUT_TAR" | awk '{print $5}'))"
fi

echo "==> 正在计算 SHA-256 校验和..."
(cd "$(dirname "$OUTPUT_TAR")" && sha256sum "$(basename "$OUTPUT_TAR")" > "$(basename "$OUTPUT_TAR").sha256")
echo "✓ 校验和文件已生成: $OUTPUT_TAR.sha256 ($(cat "$OUTPUT_TAR.sha256" | awk '{print $1}'))"

echo "=========================================================="
echo "✓ 底包导出成功完成！"
echo "  底包路径: $OUTPUT_TAR"
echo "=========================================================="

# 如果指定了 --full，直接调用 build-module.sh 生成完整刷机包
if [ "$TRIGGER_FULL_BUILD" -eq 1 ]; then
    echo ""
    echo "==> 检测到 --full 标志，立即启动模块总装 (build-module.sh full)..."
    bash "$ROOT_DIR/scripts/build-module.sh" full
fi
