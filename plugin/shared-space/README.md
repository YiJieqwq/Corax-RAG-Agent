
# Corax-RAG

> 墨鸦 Strata v5.1.0 (C-VFS) — 轻量级 Agentic RAG，让 AI 像乌鸦一样记住你。

---

## 简介

Corax-RAG（墨鸦 Strata）是一个运行在 QQ 环境下的轻量级 Agentic RAG 框架。采用 QFun Plugin (BeanShell)，使用 DeepSeek v4 Flash 模型，为群聊和私聊提供长期记忆、身份隔离和 AI 助手能力。

**v5.1.0：** Corax-Shell 虚拟文件系统 + 快照审批 + 安全写保护 + 熔断器

---

## 快速开始

1. 下载 Releases 集成包 → 解压到 QFun 插件目录
2. `/ai set api_key sk-xxx` 配置 API key
3. `/ai on` 启用 AI
4. 默认人设"小猫"（唤醒词：`小猫/猫猫/小喵`），`/ai reboot 陈千语` 切换

---

## Corax-Shell

AI 拥有虚拟 Linux 文件系统，所有能力通过 `shell(cmd)` 暴露：

| 路径 | 功能 |
|------|------|
| `/etc/` | 配置文件（安全文件写入需审批） |
| `/proc/` | 系统状态、人设管理 |
| `/dev/` | 消息接口、后台输出 |
| `/persist/` | 持久化存储 |
| `/var/` | 数据库 + 日志 |
| AI 可执行管道(|)、重定向(>)、后台(&)、联网搜索、记忆管理。 |

### 安全审批

写入 `/etc/admins.txt`、`blocked.txt`、`members.txt`、`enabled_conversations.txt`、`listen_sessions.txt`、`default_account.txt` 需管理员 `/ai operation permit` 审批。

### 快照

写操作自动保存旧版本到 `.snapshots/`，AI 可随时回滚。`corax-snapshot-list/restore/rm`。

---

## 核心技术

| 层 | 名称 | 功能 |
|----|------|------|
| Strata | 热冷分层记忆 | 高频浮选 + 冷标签补集 |
| DREX | 格式化执行器 | tool_calls 分流 → 归档 |
| CAST | 三段式可信度 | 自述 > 转述 > 未知 |
| WARDEN-I | 三层身份隔离 | 协议层 → 系统层 → 用户层 |
| STREAM | 稳定上下文 | 前缀缓存 + 末尾注入 + 落盘 |

---

## 命令参考

```
/ai <内容>                    # AI 对话
/ai on/off/status             # 启用/禁用
/ai operation permit/reject   # 审批操作
/ai memory/search/public      # 记忆管理
/ai listen on/off/summary     # 监听模式
/ai set <k> <v> / config      # 配置
/ai reboot [name]             # 切换人设
/admin /block /member         # 权限
```

---

## 配置

| 键 | 默认 | 说明 |
|----|------|------|
| `model` | `deepseek-v4-flash` | 模型 |
| `context_ttl` | `0` | 过期分钟（0=永不过期） |
| `shell_rounds` | `8` | 最大 shell 轮数 |
| `temperature` | `0.7` | 温度 |

---

## 协议

**MIT** © 2026 YiJieqwq异界 · 感谢 Shixiaoshi0417 的持续贡献
