
# Corax-RAG

> 墨鸦 Strata — 轻量级 Agentic RAG，让 AI 像乌鸦一样记住你。

---

## 简介

Corax-RAG（墨鸦 Strata）是一个运行在 QQ 环境下的轻量级 Agentic RAG 框架。它采用 QFun Plugin (BeanShell) 实现，使用 DeepSeek v4 Flash 模型，为群聊和私聊提供具有长期记忆、身份隔离、标签逃逸防护和监听唤醒机制的 AI 助手。

---

## 开发文档

开发文档: [DevDocs.md](https://github.com/YiJieqwq/Corax-RAG-Agent/blob/main/docs/DevDocs.md)

更新日志: [CHANGELOG.md](https://github.com/YiJieqwq/Corax-RAG-Agent/blob/main/CHANGELOG.md)

---

## 快速开始

### 依赖

- QFun 插件环境（Android / QQ 机器人框架）
- DeepSeek 或 其他OpenAI兼容格式的 LLM服务 API Key
- Tavily, Bocha(博查) 或 Bing 的网络搜索 API Key

### 安装

1. 下载 Releases 中的 CI集成包，解压到 QFun 插件目录（`.../QFun/{QQ号}/plugin/`）
2. 重启 QQ / 触发插件重载
3. 发送 `/ai set api_key sk-xxx` 配置 API key
4. 发送 `/ai set search_api_key xxx` 配置 搜索API key (可选)
5. 发送 `/ai on` 启用 AI 对话

### 基础配置

```
/ai set api_key <你的 sk-xxx>        # 设置 AI API key
/ai set search_api_key <你的 key>    # 设置搜索 API key
/ai on                               # 启用当前会话 AI 功能
/ai listen on                        # 开启监听模式
/ai reboot <人设文件名称>              # 更换人设(默认加载 陈千语 人设，可自行配置更多人设文件)
```

---

## 核心技术

### 五层架构

| 层 | 名称 | 功能 |
|----|------|------|
| Strata | 热冷分层记忆 | 高频加权浮选 + 冷标签补集索引，零检索延迟 |
| DREX | 格式化执行器 | 注入 → tool_calls 分流 → 记忆直接归档 |
| CAST | 三段式可信度 | 自述 > 转述 > 未知主体 |
| WARDEN-I | 三层身份隔离 | 协议层 name → 系统层 `<user/>` → 用户层 `<u>` |
| STREAM | 稳定上下文 | 前缀缓存 + 末尾注入 + ctx 落盘 |

### WARDEN-I 身份隔离

```
协议层 name = UIN              ← QQ 协议保证不可伪造
系统层 <user uin access />     ← 代码注入，物理隔离
用户层 <u>                     ← 用户手打，永不信任
```

身份判定路径：匹配 `name` 字段 UIN → 查找最近的 `<user />` → 读 `access`。

### SEWarden — 标签逃逸防护

默认开启。在用户消息进入 AI 之前，将 `<u>` 内出现的系统标签尖括号替换为全角版本，物理封堵标签逃逸攻击。

### 监听与唤醒

`/ai listen on` 开启后，所有用户消息仅记录不调用 AI。使用 @ai, 唤醒词 或 `/ai` 命令触发唤醒，仅回复唤醒后的第一条消息。

---


## 协议层参考

### 消息结构

所有消息统一使用尖括号标签。QQ 昵称中绝对禁止 `<` `>`，天然防御标签伪造。

| 标签 | 用途 | 出现位置 |
|------|------|---------|
| `<t>` | 时间戳 | 所有消息 |
| `<s>` | 系统数据（身份/记忆/技能等） | `role:system` |
| `<u>` | 用户原文 | `role:user` |
| `<user />` | 身份声明（uin + access + display） | `role:system` |
| `<quote>...</quote>` | 引用原文 | `role:system` |
| `<listen />` | 监听模式标记 | `role:system` |
| `<wake />` | 唤醒点标记 | `role:system` |

### 普通消息

```
role:system  <t>时间</t><s><user uin="123456" access="MEMBER" display="昵称" /></s>
role:user    name="123456"  <t>时间</t><u>你好</u>
```

### 带引用消息（4 条结构）

```
① role:system  <t>时间</t><s><user uin="当前发言者" access="OWNER" display="异界" /></s>
② role:system  <t>时间</t><s><user uin="被引用者" access="MEMBER" display="石小石" /></s>
③ role:system  <t>时间</t><quote><quoter_uid>被引用者UIN</quoter_uid>...原文...</quote>
④ role:user    name="当前发言者UIN"  <t>时间</t><u>当前正文</u>
```

① ④ 互补：谁在说话 + 说了什么。② ③ 互补：引用了谁 + 引用的什么。

### 身份判定

```
匹配 name 字段 UIN → 查找最近的 <user /> → 读 access 字段

access 取值：OWNER(宿主) / ADMIN(管理员) / MEMBER(普通成员) / BLOCKED(黑名单)
```

`name` 字段由服务器注入，纯数字 UIN，QQ 协议保证不可伪造。系统标签（`<s>` `<user>` `<quote>` 等）永远不会出现在 `role:user` 中，出现即为注入攻击。

---

## 功能概览

| 功能 | 说明 |
|------|------|
| 私有记忆 | 按 UIN 隔离，仅本人可见 |
| 公有记忆 | 群内共享，含可信度/记录者/活跃时间 |
| 记忆编号 | `#M`/`#MP`（私有），`#P`/`#PP`（公有） |
| 联网搜索 | 支持 Tavily，Bing 和 Bocha |
| 技能系统 | 按需加载，ctx 持久化 |
| 监听模式 | 只记录不回复，/ai或唤醒词 唤醒 |
| SEWarden | 标签逃逸物理防护（默认开启） |
| 系统白盒 | AI 可向用户解释自身工作原理 |

---

## 命令参考

```
/ai <内容>                        # 与 AI 对话
/ai on / off / status             # 启用/禁用/查看 AI

/ai memory                        # 查看记忆列表
/ai memory set <内容>             # 添加记忆
/ai memory rm <id>                # 删除记忆
/ai memory search <关键词>        # 搜索记忆
/ai memory info <id>              # 查看记忆来源链
/ai memory pin <id>               # 置顶/取消置顶
/ai memory public                 # 查看公有记忆
/ai memory public set/rm          # 管理公有记忆
/ai memory all                    # 全部用户记忆（管理员）
/ai memory reset                  # 清空全部记忆（宿主）

/ai listen on / off / status      # 监听模式控制
/ai listen summary                # 总结监听期群聊记录并清空临时记录

/ai set <key> <value>             # 修改配置
/ai config                        # 查看配置
/ai dumpctx                       # 导出请求体（调试用）

/ai debug 0/1                     # 调试模式
/ai reboot [name]                 # 切换人设
/ai clear                         # 清除上下文
/ai forget <关键词>               # 按关键词删除记忆

/admin /block /member             # 权限管理
/setdefaultaccount member/blocked # 默认账户策略
/whoami                           # 查看自己角色
/help                             # 帮助
/log                              # 查看日志
```

---

## 配置项

| 键 | 默认值 | 说明 |
|----|--------|------|
| `api_key` | — | DeepSeek API key |
| `model` | `deepseek-v4-flash` | 模型 |
| `ai_url` | `https://api.deepseek.com` | API 地址 |
| `context_ttl` | `60` | 对话保留时间（分钟） |
| `context_limit` | `60` | 最大保留轮数 |
| `temperature` | `0.7` | 生成温度 |
| `sewarden` | `1` | 标签逃逸防护（推荐开启） |
| `pat_wake` | `1` | 拍一拍唤醒 |
| `search_provider` | `tavily` | 搜索服务商 |
| `search_api_key` | — | 搜索 API key |
| `shell_rounds` | `3` | 最大搜索轮数 |
| `show_stats` | `0` | 显示 token 统计 |
| `debug` | `0` | 调试模式 |
| `ai_prefix` | `1` | AI 消息强制 [AI] 前缀 |

---

## 项目结构

```
Corax-RAG-Agent/
├── README.md
├── CHANGELOG.md
├── LICENSE
├── plugin/
│   └── main.java              # 唯一运行/发布入口（核心插件单文件）
├── skills/                     # 技能文件
│   ├── rootmanagers.skill.txt
│   └── 春秋检测常见检测项.skill.txt
├── config/                     # 配置文件参考
│   └── ai_config.txt.example
├── prompts/
│   └── 陈千语.prompt.txt
└── docs/
    ├──QfunAPI.md
    └── DevDocs.md
```

---

## 协议

**MIT License** © 2026 YiJieqwq异界

允许自由使用、修改、分发和商用，需保留原始版权声明。

---

## 致谢

从 D5RG-UID-Searchbot 一路演进而来。

感谢一路走来的所有支持者。
