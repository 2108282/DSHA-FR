# DSHA 外部设备技能

本目录只维护 [external-device-mesh](external-device-mesh/SKILL.md)，用于明确指定的外部 Android 设备与 SSH 主机。技能包含连接配置定位、按已有配置连接、失败恢复和远程文件传输要求。

本机手机操作使用当前运行环境提供的 `mcp__phone__*` 工具；设备操作通道与安全边界由后端设备提示插件维护。MCP 不可用、被拒绝或需要用户处理时，说明原因或交接用户，不改用本地 HTTP、ADB 或 Root 命令绕过。

## 安装

在本仓库的 `agent-skills/` 目录下，将整个技能目录复制到目标 Agent 支持的技能目录：

```sh
mkdir -p ~/.agents/skills
cp -r external-device-mesh ~/.agents/skills/
```

保留 `external-device-mesh/SKILL.md` 这一层结构。目标 Agent 应支持标准技能 frontmatter；外部连接还需要该环境已安装相应客户端，并配置已获授权的目标设备或主机。不要把真实密码、私钥、配对码或个人设备清单提交到本仓库。
